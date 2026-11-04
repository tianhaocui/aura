package io.aura.web;

import com.alibaba.fastjson2.JSON;
import io.aura.AuraConfig;
import io.aura.snapshot.SnapshotOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotRedactionTest {

    @TempDir
    Path tempDir;

    @Test
    void writtenSnapshotKeepsApplicationFramesButRedactsAbsolutePath() throws Exception {
        AuraConfig config = new AuraConfig().snapshot(s -> s.enabled(true).async(false).dir(tempDir));
        RuntimeException failure = new RuntimeException("boom");
        failure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.acme.billing.VaultService", "charge",
                        "/Users/foo/secretpath/VaultService.java", 42),
                new StackTraceElement("io.aura.web.Router", "handle", "Router.java", 10)
        });
        Router router = new Router();
        router.get("/vault", ctx -> {
            throw failure;
        });

        TestApplications.client(config, router).get("/vault").execute();

        String json = Files.readString(Files.list(tempDir).findFirst().orElseThrow());
        // The application frame is what makes the failure diagnosable, so it survives; only the
        // absolute source path -- which can expose the deployment layout -- is scrubbed.
        assertThat(json)
                .contains("com.acme.billing.VaultService", "charge", "io.aura.web.Router")
                .doesNotContain("secretpath")
                .contains("<redacted-path>")
                .doesNotContain("<redacted-frame>");
    }

    @Test
    void stackTextRedactsPathsAndSecretsButKeepsEveryFrame() {
        SnapshotRedactor redactor = new SnapshotRedactor(new SnapshotOptions());
        String stack = "java.lang.RuntimeException\n"
                + "\\tat com.acme.billing.Class.run(/Users/foo/secretpath/config.yml:1)\n"
                + "\\tat java.base/java.lang.Thread.run(Thread.java:840)\n"
                + "Caused by: com.acme.billing.RootCause.fail\n";

        String result = redactor.stackText(stack);

        assertThat(result)
                .doesNotContain("secretpath", "config.yml")
                .contains("<redacted-path>")
                .contains("com.acme.billing.Class.run", "java.base/java.lang.Thread.run",
                        "com.acme.billing.RootCause.fail")
                .doesNotContain("<redacted-frame>");
        assertThat(redactor.stackText(result)).isEqualTo(result);
    }

    @Test
    void stackTextRedactsFileAndWindowsPaths() {
        SnapshotRedactor redactor = new SnapshotRedactor(new SnapshotOptions());
        String unix = redactor.stackText(
                "Caused by: java.io.FileNotFoundException: file:/home/deploy/secretpath/app.properties");
        assertThat(unix).doesNotContain("secretpath").contains("<redacted-path>");

        String windows = redactor.stackText("\\tat com.acme.Loader.load(C:\\aura\\secretpath\\Loader.java:7)");
        assertThat(windows).doesNotContain("secretpath").contains("<redacted-path>")
                .contains("com.acme.Loader.load");
    }

    @Test
    void proxyAuthorizationHeaderIsRedacted() {
        SnapshotRedactor redactor = new SnapshotRedactor(new SnapshotOptions());
        assertThat(redactor.headers(Map.of("Proxy-Authorization", "Basic xyz123")))
                .containsEntry("Proxy-Authorization", "<redacted>");
    }

    @Test
    void fuzzyTokenHeaderIsRedacted() {
        SnapshotRedactor redactor = new SnapshotRedactor(new SnapshotOptions());
        assertThat(redactor.headers(Map.of("X-My-Token", "abc")))
                .containsEntry("X-My-Token", "<redacted>");
    }

    @Test
    void benignHeaderIsNotRedacted() {
        SnapshotRedactor redactor = new SnapshotRedactor(new SnapshotOptions());
        assertThat(redactor.headers(Map.of("X-Request-Id", "xyz")))
                .containsEntry("X-Request-Id", "xyz");
    }

    @Test
    void apiKeyJsonBodyFieldIsRedacted() {
        SnapshotRedactor redactor = new SnapshotRedactor(new SnapshotOptions());
        String json = JSON.toJSONString(
                redactor.body("{\"api_key\":\"sk-live-abcdef\",\"name\":\"ada\"}", 1024));
        assertThat(json).doesNotContain("sk-live-abcdef");
        assertThat(JSON.parseObject(json)).containsEntry("api_key", "<redacted>").containsEntry("name", "ada");
    }
}
