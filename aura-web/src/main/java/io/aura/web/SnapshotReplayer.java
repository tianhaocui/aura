package io.aura.web;

import com.alibaba.fastjson2.JSON;
import io.aura.Aura;
import io.aura.snapshot.ExceptionSnapshot;
import io.aura.snapshot.Snapshot;
import io.aura.snapshot.SnapshotScope;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class SnapshotReplayer {

    private final Aura app;
    private ExceptionSnapshot snapshot;

    private SnapshotReplayer(Aura app) {
        this.app = app;
    }

    public static SnapshotReplayer of(Aura app) {
        return new SnapshotReplayer(app);
    }

    public SnapshotReplayer load(String path) {
        return load(Path.of(path));
    }

    public SnapshotReplayer load(Path path) {
        try {
            this.snapshot = JSON.parseObject(Files.readString(path), ExceptionSnapshot.class);
            return this;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to load snapshot: " + path, e);
        }
    }

    public ReplayResult replay() {
        if (snapshot == null) throw new IllegalStateException("No snapshot loaded");
        SnapshotScope scope = SnapshotScope.replay(snapshot);
        try {
            return Snapshot.withScope(scope, () -> {
                TestClient.Request request = request(TestClient.of(app), snapshot.request());
                TestClient.Response response = request.execute();
                return new ReplayResult(snapshot, response, envDiff(snapshot.environment()));
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static TestClient.Request request(TestClient client, ExceptionSnapshot.RequestSnapshot req) {
        String path = req.path();
        if (req.query() != null && !req.query().isEmpty() && !path.contains("?")) {
            path += "?" + req.query().entrySet().stream()
                    .map(e -> encodeQueryComponent(e.getKey()) + "=" + encodeQueryComponent(e.getValue()))
                    .reduce((a, b) -> a + "&" + b)
                    .orElse("");
        }
        TestClient.Request request = switch (req.method()) {
            case "POST" -> client.post(path);
            case "PUT" -> client.put(path);
            case "DELETE" -> client.delete(path);
            case "PATCH" -> client.patch(path);
            case "HEAD" -> client.head(path);
            case "OPTIONS" -> client.options(path);
            default -> client.get(path);
        };
        if (req.headers() != null) {
            req.headers().forEach((k, v) -> {
                if (!"<redacted>".equals(v)) request.header(k, v);
            });
        }
        if (req.body() != null && !"GET".equals(req.method()) && !"HEAD".equals(req.method())) {
            request.body(req.body() instanceof String s ? s : JSON.toJSONString(req.body()));
        }
        return request;
    }

    private static String encodeQueryComponent(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private Map<String, String> envDiff(ExceptionSnapshot.EnvSnapshot env) {
        Map<String, String> diff = new LinkedHashMap<>();
        if (env == null) return diff;
        compare(diff, "aura.env", env.auraEnv(), app.env());
        compare(diff, "java.version", env.javaVersion(), System.getProperty("java.version"));
        compare(diff, "os.name", env.osName(), System.getProperty("os.name"));
        compare(diff, "timezone", env.timezone(), java.util.TimeZone.getDefault().getID());
        return diff;
    }

    private static void compare(Map<String, String> diff, String key, String expected, String actual) {
        if (!Objects.equals(expected, actual)) diff.put(key, "snapshot=" + expected + ", current=" + actual);
    }

    public record ReplayResult(ExceptionSnapshot snapshot, TestClient.Response response, Map<String, String> envDiff) {
        public ReplayResult expectStatus(int status) {
            response.expect(status);
            return this;
        }

        public ReplayResult expectSameStatus() {
            return expectStatus(snapshot.response().status());
        }

        public ReplayResult expectCode(String code) {
            Object actual = JSON.parseObject(response.body()).get("code");
            if (!Objects.equals(code, actual)) {
                throw new AssertionError("Expected code " + code + " but got " + actual + " body: " + response.body());
            }
            return this;
        }

        public ReplayResult expectSameCode() {
            String code = snapshot.exception() != null ? snapshot.exception().code() : null;
            if (code == null) return this;
            return expectCode(code);
        }

        public ReplayResult expectNoEnvDiff() {
            if (!envDiff.isEmpty()) throw new AssertionError("Environment differs: " + envDiff);
            return this;
        }
    }
}
