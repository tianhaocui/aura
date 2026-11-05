package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.contract.Route;
import io.aura.port.BaseHandler;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An in-memory request is a stand-in for a socket request, so every place the two disagree is a
 * suite that runs green against a server the application never actually runs.
 */
class MockContextParityTest {

    @Test
    void theRequestVerbReachesTheHandler() {
        AtomicReference<String> seen = new AtomicReference<>();
        AuraApp app = TestApplications.app("parity-method",
                Route.of("POST", "/verb", (BaseHandler) ctx -> {
                    seen.set(ctx.method());
                    ctx.text("ok");
                }));

        TestClient.of(app).post("/verb").execute();

        assertThat(seen.get()).isEqualTo("POST");
    }

    @Test
    void aRedirectCarriesTheLocationItRedirectsTo() {
        AuraApp app = TestApplications.app("parity-redirect",
                Route.of("GET", "/old", (BaseHandler) ctx -> ctx.redirect("/new")));

        TestClient.Response response = TestClient.of(app).get("/old").execute();

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.header("Location")).isEqualTo("/new");
    }

    @Test
    void aSentFileAnnouncesItselfInTheHeaders() {
        AuraApp app = TestApplications.app("parity-sendfile",
                Route.of("GET", "/report", (BaseHandler) ctx -> ctx.sendFile("report.csv",
                        "a,b\n1,2".getBytes(StandardCharsets.UTF_8), "text/csv")));

        TestClient.Response response = TestClient.of(app).get("/report").execute();

        assertThat(response.header("Content-Type")).isEqualTo("text/csv");
        assertThat(response.header("Content-Disposition")).isEqualTo("attachment; filename=\"report.csv\"");
    }

    @Test
    void aSecondResponseDoesNotOverwriteTheFirst() {
        AuraApp app = TestApplications.app("parity-one-response",
                Route.of("GET", "/twice", (BaseHandler) ctx -> {
                    ctx.text("first");
                    ctx.text("second");
                }));

        assertThat(TestClient.of(app).get("/twice").execute().body()).isEqualTo("first");
    }

    @Test
    void aFormTheRequestNeverCarriedIsAbsentRatherThanUnreadable() {
        AtomicReference<String> value = new AtomicReference<>("unset");
        AuraApp app = TestApplications.app("parity-form",
                Route.of("POST", "/form", (BaseHandler) ctx -> {
                    value.set(ctx.formField("missing"));
                    ctx.text("ok");
                }));

        TestClient.of(app).post("/form").body("{}").execute();

        assertThat(value.get()).isNull();
    }

    @Test
    void aJsonResponseCarriesTheSameContentTypeAsASocketResponse() {
        AuraApp app = TestApplications.app("parity-json",
                Route.of("GET", "/json", (BaseHandler) ctx -> ctx.json(Map.of("ok", true))));

        TestClient.Response response = TestClient.of(app).get("/json").execute();

        assertThat(response.header("Content-Type")).isEqualTo("application/json; charset=utf-8");
    }

    @Test
    void aTextResponseCarriesTheSameContentTypeAsASocketResponse() {
        AuraApp app = TestApplications.app("parity-text",
                Route.of("GET", "/text", (BaseHandler) ctx -> ctx.text("hello")));

        TestClient.Response response = TestClient.of(app).get("/text").execute();

        assertThat(response.header("Content-Type")).isEqualTo("text/plain; charset=utf-8");
    }

    @Test
    void changingStatusOrHeadersAfterTheResponseStartedIsIgnored() {
        AuraApp app = TestApplications.app("parity-started",
                Route.of("GET", "/started", (BaseHandler) ctx -> {
                    ctx.text("first");
                    ctx.status(500).header("X-Late", "1");
                }));

        TestClient.Response response = TestClient.of(app).get("/started").execute();

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("first");
        assertThat(response.header("X-Late")).isNull();
    }

    @Test
    void aHeaderCarryingLineBreaksCannotSmuggleASecondHeader() {
        AtomicReference<RuntimeException> rejected = new AtomicReference<>();
        AuraApp app = TestApplications.app("parity-header-injection",
                Route.of("GET", "/headers", (BaseHandler) ctx -> {
                    try {
                        ctx.header("X-Bad", "value\r\nX-Injected: yes");
                    } catch (RuntimeException failure) {
                        rejected.set(failure);
                    }
                    ctx.text("ok");
                }));

        TestClient.Response response = TestClient.of(app).get("/headers").execute();

        assertThat(response.status()).isEqualTo(200);
        assertThat(rejected.get()).isInstanceOf(IllegalArgumentException.class);
        assertThat(response.header("X-Injected")).isNull();
    }

    @Test
    void anOversizedBodyIsRefusedWith413BeforeTheHandlerRuns() {
        AtomicBoolean ran = new AtomicBoolean();
        AuraConfig config = new AuraConfig().maxBodySize(64).maxUploadSize(64);
        AuraApp app = TestApplications.app(config, "parity-limit",
                Route.of("POST", "/body", (BaseHandler) ctx -> {
                    ran.set(true);
                    ctx.text("ok");
                }));

        TestClient.Response response = TestClient.of(app).post("/body")
                .body("x".repeat(65)).execute();

        assertThat(response.status()).isEqualTo(413);
        assertThat(ran).isFalse();
        assertThat(response.body()).contains("REQUEST_BODY_TOO_LARGE");
    }
}
