package io.aura.web;

import com.alibaba.fastjson2.JSON;
import io.aura.Aura;
import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;
import io.aura.contract.RouteContribution;
import io.aura.snapshot.ExceptionSnapshot;
import io.aura.snapshot.Snapshot;
import io.aura.snapshot.SnapshotScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;
import io.aura.contract.CancellationToken;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotReplayTest {

    @TempDir
    Path tempDir;

    @Test
    void snapshotRecordsArtifactsExternalCallsAndRedactsSensitiveData() throws Exception {
        Aura app = Aura.create().snapshot(s -> s.enabled(true).async(false).dir(tempDir));
        Router router = new Router();
        router.post("/checkout", ctx -> {
            Snapshot.put("systemX.data", Map.of("level", "VIP", "paymentToken", "tok_123"));
            Snapshot.externalCall(new ExceptionSnapshot.ExternalCallSnapshot(
                    "call-1", "systemX", "http", "GET", "https://x.example.com/users/u1",
                    Map.of("Authorization", "Bearer upstream"), null, 200, Map.of(),
                    Map.of("level", "VIP", "paymentToken", "tok_123"), 12, Instant.now().toString()));
            throw new ApiException(400, "PAYMENT_REJECTED", "payment rejected", "payment");
        });

        TestClient.Response resp = new TestClient(app, router)
                .post("/checkout")
                .header("Authorization", "Bearer inbound")
                .body(Map.of("userId", "u1", "password", "secret"))
                .execute();

        assertThat(resp.status()).isEqualTo(400);
        List<Path> files = Files.list(tempDir).toList();
        assertThat(files).hasSize(1);
        String json = Files.readString(files.get(0));
        assertThat(json).contains("systemX.data");
        assertThat(json).contains("PAYMENT_REJECTED");
        assertThat(json).contains("\"Authorization\":\"<redacted>\"");
        assertThat(json).contains("\"paymentToken\":\"<redacted>\"");
        assertThat(json).contains("\"password\":\"<redacted>\"");
        assertThat(json).doesNotContain("Bearer inbound");
        assertThat(json).doesNotContain("tok_123");
        assertThat(json).doesNotContain("secret");
        assertThat(json).doesNotContain("/users/u1");
        assertThat(json).contains("/.aura-redacted/");
    }

    @Test
    void snapshotRedactsTopLevelArtifactKeysAndNonSensitiveHeaderValuesOnDisk() throws Exception {
        Aura app = Aura.create().snapshot(s -> s.enabled(true).async(false).dir(tempDir));
        Router router = new Router();
        router.get("/capture", ctx -> {
            Snapshot.put("token", "raw-artifact-secret");
            throw new RuntimeException("failed");
        });

        new TestClient(app, router)
                .get("/capture")
                .header("X-Debug", "password=header-secret https://headers.example/private?token=url-secret")
                .execute();

        String json = Files.readString(Files.list(tempDir).findFirst().orElseThrow());
        ExceptionSnapshot snapshot = JSON.parseObject(json, ExceptionSnapshot.class);
        String debugHeader = snapshot.request().headers().entrySet().stream()
                .filter(entry -> "X-Debug".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
        assertThat(snapshot.artifacts()).containsEntry("token", "<redacted>");
        assertThat(debugHeader)
                .contains("password=<redacted>", "https://headers.example/.aura-redacted/")
                .doesNotContain("header-secret", "/private", "url-secret");
        assertThat(json).doesNotContain("raw-artifact-secret", "header-secret", "/private", "url-secret");
    }

    @Test
    void snapshotStackTraceStrictlyHonorsConfiguredCapsWithoutThrowableMessages() throws Exception {
        int regularCap = 40;
        ExceptionSnapshot regular = recordStackSnapshot(tempDir.resolve("regular"), regularCap);

        assertThat(regular.exception().message()).isNull();
        assertThat(regular.exception().rootCauseMessage()).isNull();
        assertThat(regular.exception().stackTrace())
                .hasSize(regularCap)
                .endsWith("...<truncated>")
                .doesNotContain("outer-stack-secret", "inner-stack-secret");

        int smallCap = 5;
        ExceptionSnapshot small = recordStackSnapshot(tempDir.resolve("small"), smallCap);
        assertThat(small.exception().stackTrace()).isEqualTo("...<t").hasSize(smallCap);
    }

    @Test
    void snapshotBodyTruncationUsesStrictUtf8ByteLimits() {
        SnapshotRedactor redactor = new SnapshotRedactor(new io.aura.snapshot.SnapshotOptions());

        assertThat(redactor.body("abcdefgh", 5)).isEqualTo("...<t");
        String multibyte = (String) redactor.body("abcdefghijklmnop\uD83D\uDE00", 16);
        assertThat(multibyte.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(16);
        assertThat(multibyte).contains("...<truncated>");
        assertThat(redactor.body("secret", 0)).isNull();
    }

    @Test
    void snapshotRetentionEvictsOldFilesAndReportsDrops() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> drop = new java.util.concurrent.atomic.AtomicReference<>();
        Aura app = Aura.create().snapshot(s -> s.enabled(true).async(false).dir(tempDir)
                .maxSnapshots(1).maxStorageSize(1024 * 1024).onDrop(drop::set));
        Router router = new Router();
        router.get("/fail", ctx -> { throw new RuntimeException("failure"); });

        new TestClient(app, router).get("/fail").execute();
        Path first = Files.list(tempDir).findFirst().orElseThrow();
        new TestClient(app, router).get("/fail").execute();

        assertThat(Files.list(tempDir).toList()).hasSize(1);
        assertThat(Files.list(tempDir).findFirst().orElseThrow()).isNotEqualTo(first);
        assertThat(drop.get()).isNull();
    }

    @Test
    void snapshotRetentionReportsDropWhenStorageCannotFitOneFile() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> drop = new java.util.concurrent.atomic.AtomicReference<>();
        Aura app = Aura.create().snapshot(s -> s.enabled(true).async(false).dir(tempDir)
                .maxSnapshots(1).maxSnapshotFileSize(1).onDrop(drop::set));
        Router router = new Router();
        router.get("/fail", ctx -> { throw new RuntimeException("failure"); });

        new TestClient(app, router).get("/fail").execute();

        assertThat(drop.get()).isEqualTo("file-too-large");
        assertThat(SnapshotRecorder.dropped(app)).isEqualTo(1L);
    }

    @Test
    void replayerStubsExternalCallsFromSnapshot() throws Exception {
        Path snapshotFile = tempDir.resolve("replay.json");
        ExceptionSnapshot snapshot = snapshot();
        Files.writeString(snapshotFile, JSON.toJSONString(snapshot));

        AuraApp app = AuraApp.create().use(routes("replay-payment",
                new RouteContribution("POST", "/pay", (BaseHandler) ctx -> {
            UserDto user = new SnapshotHttpClient("systemX", "https://x.example.com")
                    .get("/users/u1", UserDto.class);
            Snapshot.put("systemX.user", user);
            throw new ApiException(400, "PAYMENT_REJECTED", "payment rejected for " + user.level());
        }))).prepare();

        SnapshotReplayer.ReplayResult result = SnapshotReplayer.of(app)
                .load(snapshotFile)
                .replay()
                .expectSameStatus()
                .expectSameCode();

        assertThat(result.response().body()).contains("payment rejected for VIP");
    }

    @Test
    void replayExternalHttpErrorsAsApiExceptions() {
        ExceptionSnapshot base = snapshot();
        ExceptionSnapshot.ExternalCallSnapshot failed = new ExceptionSnapshot.ExternalCallSnapshot(
                "call-failed", "systemX", "http", "GET", "https://x.example.com/users/u1",
                Map.of(), null, 503, Map.of(), Map.of("error", "upstream down"), 10, Instant.now().toString());
        ExceptionSnapshot replay = new ExceptionSnapshot(base.schemaVersion(), base.snapshotId(), base.createdAt(),
                base.request(), base.route(), base.exception(), base.response(), base.environment(), List.of(failed), base.artifacts());

        SnapshotHttpClient client = new SnapshotHttpClient("systemX", "https://x.example.com");
        SnapshotScope scope = SnapshotScope.replay(replay);
        try {
            Snapshot.bind(scope);
            assertThatThrownBy(() -> client.get("/users/u1", UserDto.class))
                    .isInstanceOf(ApiException.class)
                    .satisfies(error -> {
                        ApiException api = (ApiException) error;
                        assertThat(api.status()).isEqualTo(503);
                        assertThat(api.code()).isEqualTo("SYSTEMX_HTTP_503");
                        assertThat(api.getMessage()).isEqualTo("External call failed: systemX 503");
                    });
        } finally {
            Snapshot.clear();
        }
    }

    @Test
    void failedExternalCallsAreRecordedForReplay() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/stalled", exchange -> {
            exchange.sendResponseHeaders(200, 1);
            try (OutputStream output = exchange.getResponseBody()) {
                output.flush();
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            URI base = URI.create("http://localhost:" + server.getAddress().getPort());
            SnapshotScope scope = SnapshotScope.capture();
            SnapshotHttpClient client = new SnapshotHttpClient("local", base,
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                    Duration.ofMillis(50), 1024);
            try {
                Snapshot.withScope(scope, () -> {
                    assertThatThrownBy(() -> client.get("/stalled", String.class))
                            .isInstanceOf(ApiException.class)
                            .satisfies(error -> {
                                ApiException api = (ApiException) error;
                                assertThat(api.status()).isIn(502, 504);
                            });
                    return null;
                });
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            assertThat(scope.externalCalls()).hasSize(1);
            String recordedCode = String.valueOf(((Map<?, ?>) scope.externalCalls().get(0).responseBody()).get("code"));
            int recordedStatus = scope.externalCalls().get(0).responseStatus();
            assertThat(recordedStatus).isIn(502, 504);
            assertThat(scope.externalCalls().get(0).responseBody()).isInstanceOf(Map.class);
            assertThat(recordedCode).isIn("LOCAL_TIMEOUT", "LOCAL_UNAVAILABLE", "LOCAL_RESPONSE_TOO_LARGE");

            ExceptionSnapshot captured = new ExceptionSnapshot(1, "captured", Instant.now().toString(),
                    null, null, null, null, null, scope.externalCalls(), Map.of());
            SnapshotScope replay = SnapshotScope.replay(captured);
            try {
                Snapshot.bind(replay);
                assertThatThrownBy(() -> client.get("/stalled", String.class))
                        .isInstanceOf(ApiException.class)
                        .satisfies(error -> {
                            ApiException api = (ApiException) error;
                            assertThat(api.status()).isEqualTo(recordedStatus);
                            assertThat(api.code()).isEqualTo(recordedCode);
                        });
            } finally {
                Snapshot.clear();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void externalCallHonorsCancellationTokenWhileReadingResponse() throws Exception {
        java.util.concurrent.CountDownLatch responseStarted = new java.util.concurrent.CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/cancel", exchange -> {
            exchange.sendResponseHeaders(200, 1);
            responseStarted.countDown();
            try (OutputStream output = exchange.getResponseBody()) {
                output.flush();
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        java.util.concurrent.ExecutorService caller = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            URI base = URI.create("http://localhost:" + server.getAddress().getPort());
            SnapshotHttpClient client = new SnapshotHttpClient("local", base,
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                    Duration.ofSeconds(5), 1024);
            CancellationToken token = new CancellationToken();
            java.util.concurrent.Future<?> request = caller.submit(() ->
                    client.get("/cancel", String.class, token));
            assertThat(responseStarted.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            token.cancel();
            assertThatThrownBy(() -> request.get(2, java.util.concurrent.TimeUnit.SECONDS))
                    .hasCauseInstanceOf(java.util.concurrent.CancellationException.class);
        } finally {
            caller.shutdownNow();
            server.stop(0);
        }
    }

    @Test
    void replayDoesNotMatchOnlyByUrlSuffix() {
        SnapshotScope scope = SnapshotScope.replay(snapshot());

        assertThatThrownBy(() -> scope.nextReplayExternalCall("systemX", "GET", "/users/u1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No replay external call matched");
    }

    @Test
    void replayUrlMatchingIncludesQueryParameters() {
        ExceptionSnapshot base = snapshot();
        ExceptionSnapshot.ExternalCallSnapshot call = new ExceptionSnapshot.ExternalCallSnapshot(
                "call-query", "systemX", "http", "GET", "https://x.example.com/users?id=1",
                Map.of(), null, 200, Map.of(), Map.of("level", "VIP"), 10, Instant.now().toString());
        ExceptionSnapshot withQuery = new ExceptionSnapshot(base.schemaVersion(), base.snapshotId(), base.createdAt(),
                base.request(), base.route(), base.exception(), base.response(), base.environment(), List.of(call), base.artifacts());

        SnapshotScope scope = SnapshotScope.replay(withQuery);
        assertThatThrownBy(() -> scope.nextReplayExternalCall("systemX", "GET", "https://x.example.com/users?id=2"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void snapshotOmitsSensitivePathQueryAndExceptionDetails() throws Exception {
        Aura app = Aura.create().snapshot(s -> s.enabled(true).async(false).dir(tempDir)
                .maxStackTraceSize(128).maxSnapshotFileSize(64 * 1024).maxStorageSize(256 * 1024));
        Router router = new Router();
        router.get("/reset", ctx -> {
            throw new RuntimeException("password=super-secret token=reset-secret");
        });

        TestClient.Response response = new TestClient(app, router)
                .get("/reset?token=reset-secret&user=alice")
                .execute();

        assertThat(response.status()).isEqualTo(500);
        String json = Files.readString(Files.list(tempDir).findFirst().orElseThrow());
        assertThat(json).contains("\"token\":\"<redacted>\"");
        assertThat(json).doesNotContain("reset-secret", "super-secret", "password=super-secret");
        assertThat(json).contains("\"path\":\"/reset\"");
    }

    @Test
    void snapshotRedactsStructuredExceptionFields() throws Exception {
        Aura app = Aura.create().snapshot(s -> s.enabled(true).async(false).dir(tempDir));
        Router router = new Router();
        router.get("/field", ctx -> {
            throw new ApiException(400, "password=secret-code", "failed", "password=field-secret");
        });

        new TestClient(app, router).get("/field").execute();

        String json = Files.readString(Files.list(tempDir).findFirst().orElseThrow());
        assertThat(json).contains("password=<redacted>")
                .doesNotContain("secret-code", "field-secret");
    }

    @Test
    void snapshotStoresRouteTemplateInsteadOfSensitivePathValue() throws Exception {
        Aura app = Aura.create().snapshot(s -> s.enabled(true).async(false).dir(tempDir));
        Router router = new Router();
        router.get("/reset/{token}", ctx -> { throw new RuntimeException("failed"); });

        new TestClient(app, router).get("/reset/reset-secret").execute();

        String json = Files.readString(Files.list(tempDir).findFirst().orElseThrow());
        assertThat(json).contains("\"path\":\"/reset/redacted\"")
                .contains("/reset/{token}")
                .doesNotContain("reset-secret");
    }

    @Test
    void dynamicPathSnapshotReplaysWithoutNotFound() throws Exception {
        AuraApp app = AuraApp.create(new AuraConfig()
                        .snapshot(s -> s.enabled(true).async(false).dir(tempDir)))
                .use(routes("replay-dynamic-path",
                        new RouteContribution("GET", "/users/{id}", (BaseHandler) ctx -> {
                    throw new ApiException(400, "USER_LOOKUP_FAILED", "user lookup failed");
                }))).prepare();

        TestClient.Response recorded = TestClient.of(app).get("/users/user-secret").execute();
        assertThat(recorded.status()).isEqualTo(400);

        Path snapshotFile = Files.list(tempDir).findFirst().orElseThrow();
        ExceptionSnapshot snapshot = JSON.parseObject(Files.readString(snapshotFile), ExceptionSnapshot.class);
        assertThat(snapshot.request().path()).isEqualTo("/users/redacted");
        assertThat(snapshot.route().pathPattern()).isEqualTo("/users/{id}");
        assertThat(snapshot.request().path()).doesNotContain("user-secret");

        SnapshotReplayer.ReplayResult replayed = SnapshotReplayer.of(app)
                .load(snapshotFile)
                .replay()
                .expectSameStatus()
                .expectSameCode();
        assertThat(replayed.response().status()).isEqualTo(400);
    }

    @Test
    void snapshotRedactorHandlesMalformedUrlWithoutRecursion() {
        SnapshotRedactor redactor = new SnapshotRedactor(new io.aura.snapshot.SnapshotOptions());

        assertThatCode(() -> redactor.query(Map.of("value", "http://["))).doesNotThrowAnyException();
        assertThat(redactor.query(Map.of("value", "http://["))).containsEntry("value", "http://[");
    }

    private static AuraModule routes(String id, RouteContribution... routes) {
        return new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of(id, "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                for (RouteContribution route : routes) context.route(route);
            }
        };
    }

    @Test
    void snapshotHttpClientRejectsPathsOutsideCanonicalBaseDirectory() {
        SnapshotHttpClient client = new SnapshotHttpClient("systemX", "https://x.example.com/api/");

        List<String> rejectedPaths = List.of(
                "https://169.254.169.254/latest/meta-data/",
                "//169.254.169.254/latest/meta-data/",
                "/escape",
                "../escape",
                "users#fragment",
                "users\\admin",
                "users%5Cadmin",
                "users%2Fadmin",
                "./users",
                "%2e/users",
                "users/..",
                "users/%2E%2E/escape"
        );
        for (String path : rejectedPaths) {
            assertThatThrownBy(() -> client.get(path, String.class))
                    .as("path %s", path)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void snapshotHttpClientRejectsBaseQueryAndFragment() {
        assertThatThrownBy(() -> new SnapshotHttpClient("systemX", "https://x.example.com/api/?tenant=one"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query, or fragment");
        assertThatThrownBy(() -> new SnapshotHttpClient("systemX", "https://x.example.com/api/#section"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query, or fragment");
    }

    @Test
    void snapshotHttpClientAcceptsRootAndContainedPathsWithQueries() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestURI().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            URI rootBase = URI.create("http://localhost:" + server.getAddress().getPort());
            HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            SnapshotHttpClient rootClient = new SnapshotHttpClient(
                    "root", rootBase, httpClient, Duration.ofSeconds(2), 1024);
            SnapshotHttpClient apiClient = new SnapshotHttpClient(
                    "api", rootBase.resolve("/api/"), httpClient, Duration.ofSeconds(2), 1024);

            assertThat(rootClient.get("/users?next=%2Fescape", String.class))
                    .isEqualTo("/users?next=%2Fescape");
            assertThat(apiClient.get("users?role=admin", String.class))
                    .isEqualTo("/api/users?role=admin");
            assertThat(apiClient.get("/api/users?role=admin", String.class))
                    .isEqualTo("/api/users?role=admin");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void snapshotHttpClientRejectsRedirectFollowingClient() {
        HttpClient redirecting = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        assertThatThrownBy(() -> new SnapshotHttpClient(
                "systemX", URI.create("https://x.example.com"), redirecting))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not follow redirects");
    }

    @Test
    void snapshotHttpClientBoundsExternalResponseBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/large", exchange -> {
            byte[] body = "0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            URI base = URI.create("http://localhost:" + server.getAddress().getPort());
            SnapshotHttpClient client = new SnapshotHttpClient("local", base,
                    HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build(),
                    java.time.Duration.ofSeconds(2), 8);
            assertThatThrownBy(() -> client.get("/large", String.class))
                    .isInstanceOf(ApiException.class)
                    .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("LOCAL_RESPONSE_TOO_LARGE"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void snapshotHttpClientTimesOutWhileReadingAStalledResponseBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/stalled", exchange -> {
            exchange.sendResponseHeaders(200, 1);
            try (OutputStream output = exchange.getResponseBody()) {
                output.flush();
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            URI base = URI.create("http://localhost:" + server.getAddress().getPort());
            SnapshotHttpClient client = new SnapshotHttpClient("local", base,
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                    Duration.ofMillis(100), 1024);
            long started = System.nanoTime();
            assertThatThrownBy(() -> client.get("/stalled", String.class))
                    .isInstanceOf(ApiException.class)
                    .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("LOCAL_TIMEOUT"));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(800));
        } finally {
            server.stop(0);
        }
    }

    private ExceptionSnapshot recordStackSnapshot(Path dir, int maxStackTraceSize) throws Exception {
        Aura app = Aura.create().snapshot(s -> s.enabled(true).async(false).dir(dir)
                .maxStackTraceSize(maxStackTraceSize));
        Router router = new Router();
        router.get("/stack", ctx -> {
            throw new RuntimeException("outer-stack-secret", new IllegalStateException("inner-stack-secret"));
        });

        new TestClient(app, router).get("/stack").execute();

        String json = Files.readString(Files.list(dir).findFirst().orElseThrow());
        return JSON.parseObject(json, ExceptionSnapshot.class);
    }

    private static ExceptionSnapshot snapshot() {
        return new ExceptionSnapshot(
                1,
                "snapshot-1",
                Instant.now().toString(),
                new ExceptionSnapshot.RequestSnapshot("POST", "/pay", Map.of(), Map.of("Content-Type", "application/json"),
                        Map.of("userId", "u1"), "127.0.0.1", "req-1"),
                new ExceptionSnapshot.RouteSnapshot("POST", "/pay", Map.of(), "test"),
                new ExceptionSnapshot.ExceptionInfo(ApiException.class.getName(), "payment rejected", "PAYMENT_REJECTED",
                        null, 400, ApiException.class.getName(), "payment rejected", "stack"),
                new ExceptionSnapshot.ResponseSnapshot(400, Map.of(), "application/json",
                        Map.of("error", "payment rejected", "code", "PAYMENT_REJECTED")),
                new ExceptionSnapshot.EnvSnapshot(null, "dev", System.getProperty("java.version"),
                        System.getProperty("os.name"), java.util.TimeZone.getDefault().getID(),
                        java.util.Locale.getDefault().toLanguageTag(), Map.of(), Map.of()),
                List.of(new ExceptionSnapshot.ExternalCallSnapshot(
                        "call-1", "systemX", "http", "GET", "https://x.example.com/users/u1",
                        Map.of(), null, 200, Map.of(), Map.of("level", "VIP"), 10, Instant.now().toString())),
                Map.of()
        );
    }

    record UserDto(String level) {}
}
