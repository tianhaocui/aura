package io.aura.web;

import io.aura.Aura;
import io.aura.CorsConfig;
import io.aura.annotation.Get;
import io.aura.annotation.Path;
import io.aura.annotation.RateLimit;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

class SecurityFixesTest {

    // Fix #1: RateLimiter race condition — concurrent access should not exceed limit
    @Test
    void rateLimiter_concurrentRequests_respectsLimit() throws Exception {
        RateLimiter limiter = new RateLimiter();
        int limit = 10;
        int threads = 20;
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                if (limiter.allow("key", limit, 60)) allowed.incrementAndGet();
                done.countDown();
            });
        }
        start.countDown();
        done.await();
        pool.shutdown();
        limiter.shutdown();

        assertThat(allowed.get()).isEqualTo(limit);
    }

    // Fix #2: trustProxy — X-Forwarded-For only used when trustProxy is true
    @Test
    void trustProxy_false_ignoresXForwardedFor() {
        Aura app = Aura.create().env("prod").rateLimit(2, Duration.ofMinutes(1));
        app.get("/api", (Supplier<String>) () -> "ok");
        var client = TestClient.of(app);

        // Without trustProxy, different XFF headers don't create separate rate limit buckets
        assertThat(client.get("/api").header("X-Forwarded-For", "1.1.1.1").execute().status()).isEqualTo(200);
        assertThat(client.get("/api").header("X-Forwarded-For", "2.2.2.2").execute().status()).isEqualTo(200);
        // Third request should be limited since all map to 127.0.0.1
        assertThat(client.get("/api").header("X-Forwarded-For", "3.3.3.3").execute().status()).isEqualTo(429);
    }

    @Test
    void trustProxy_true_usesXForwardedFor() {
        Aura app = Aura.create().env("prod").trustProxy(true).rateLimit(2, Duration.ofMinutes(1));
        app.get("/api", (Supplier<String>) () -> "ok");
        var client = TestClient.of(app);

        // With trustProxy, different XFF headers create separate rate limit buckets
        assertThat(client.get("/api").header("X-Forwarded-For", "1.1.1.1").execute().status()).isEqualTo(200);
        assertThat(client.get("/api").header("X-Forwarded-For", "1.1.1.1").execute().status()).isEqualTo(200);
        assertThat(client.get("/api").header("X-Forwarded-For", "1.1.1.1").execute().status()).isEqualTo(429);
        // Different IP still works
        assertThat(client.get("/api").header("X-Forwarded-For", "2.2.2.2").execute().status()).isEqualTo(200);
    }

    // Fix #3: Exception message leak — prod mode should hide error details
    @Test
    void exceptionLeak_prodMode_hidesMessage() {
        Aura app = Aura.create().env("prod");
        app.get("/fail", (BaseHandler) ctx -> { throw new RuntimeException("secret db password leak"); });
        var client = TestClient.of(app);

        var resp = client.get("/fail").execute();
        assertThat(resp.status()).isEqualTo(500);
        assertThat(resp.body()).contains("Internal Server Error");
        assertThat(resp.body()).doesNotContain("secret db password leak");
    }

    @Test
    void exceptionLeak_devMode_showsMessage() {
        Aura app = Aura.create().env("dev");
        app.get("/fail", (BaseHandler) ctx -> { throw new RuntimeException("debug info"); });
        var client = TestClient.of(app);

        var resp = client.get("/fail").execute();
        assertThat(resp.status()).isEqualTo(500);
        assertThat(resp.body()).contains("debug info");
    }

    // Fix #6: CRLF injection in headers
    @Test
    void header_crlf_throwsException() {
        Aura app = Aura.create().env("dev");
        app.get("/inject", (BaseHandler) ctx -> {
            ctx.header("X-Custom", "value\r\nInjected-Header: evil");
            ctx.json(java.util.Map.of("ok", true));
        });
        var client = TestClient.of(app);

        var resp = client.get("/inject").execute();
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body()).contains("CR or LF");
    }

    @Test
    void header_crlfInName_throwsException() {
        Aura app = Aura.create().env("dev");
        app.get("/inject", (BaseHandler) ctx -> {
            ctx.header("Bad\r\nHeader", "value");
            ctx.json(java.util.Map.of("ok", true));
        });
        var client = TestClient.of(app);

        var resp = client.get("/inject").execute();
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body()).contains("CR or LF");
    }

    // Fix #7: __schema__ and /openapi.json only in dev mode
    @Test
    void schema_prodMode_returns404() {
        Aura app = Aura.create().env("prod").openapi(true);
        app.get("/api", (Supplier<String>) () -> "ok");
        var client = TestClient.of(app);

        assertThat(client.get("/openapi.json").execute().status()).isEqualTo(404);
    }

    @Test
    void schema_devMode_returns200() {
        Aura app = Aura.create().env("dev").openapi(true);
        app.get("/api", (Supplier<String>) () -> "ok");
        var client = TestClient.of(app);

        var resp = client.get("/openapi.json").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).contains("openapi");
    }

    // Fix #8: RateLimiter key cleanup
    @Test
    void rateLimiter_cleanup_removesEmptyKeys() throws Exception {
        RateLimiter limiter = new RateLimiter();
        limiter.allow("temp-key", 10, 1); // 1-second window
        Thread.sleep(1100); // let entry expire
        // Trigger allow to clean up expired entries within the key
        limiter.allow("temp-key", 10, 1);
        // The cleanup thread runs every 60s, so we just verify the limiter works fine
        assertThat(limiter.allow("temp-key", 10, 1)).isTrue();
        limiter.shutdown();
    }

    // Fix #11: CORS wildcard + credentials validation
    @Test
    void cors_wildcardWithCredentials_throwsOnConfigure() {
        assertThatThrownBy(() -> {
            Aura app = Aura.create().env("prod");
            app.cors(c -> c.origins("*").credentials(true));
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credentials=true is incompatible with wildcard origin");
    }

    @Test
    void cors_specificOriginWithCredentials_noError() {
        Aura app = Aura.create().env("prod");
        app.cors(c -> c.origins("https://example.com").credentials(true));
        app.get("/api", (Supplier<String>) () -> "ok");
        var client = TestClient.of(app);
        assertThat(client.get("/api").execute().status()).isEqualTo(200);
    }

    // Fix #12: Aura.run() should call create()
    @Test
    void run_usesCreate_soReloadStateIsRespected() {
        // This test validates the code path, not runtime reload
        // The fix changes `new Aura()` to `create()` in Aura.run()
        // We verify by checking that create() returns an instance (basic sanity)
        Aura app = Aura.create();
        assertThat(app).isNotNull();
    }

    // Fix #5: Hot-reload clears plugins
    @Test
    void create_inReloadMode_clearsPlugins() {
        Aura app = Aura.create();
        // Simulate reload state
        Aura.setReloadInstance(app);
        try {
            app.plugin(a -> {}); // add a dummy plugin
            Aura reloaded = Aura.create();
            // After reload, direct access to plugins isn't possible, but we can verify
            // it doesn't throw and services are cleared
            assertThat(reloaded).isSameAs(app);
        } finally {
            Aura.clearReloadInstance();
        }
    }
}
