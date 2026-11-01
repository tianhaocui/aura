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
    void writtenSnapshotRedactsAbsolutePathsAndPrivateStackFrames() throws Exception {
        AuraConfig config = new AuraConfig().snapshot(s -> s.enabled(true).async(false).dir(tempDir));
        RuntimeException failure = new RuntimeException("boom");
        failure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.acme.private.VaultService", "charge",
                        "/Users/foo/secretpath/VaultService.java", 42),
                new StackTraceElement("io.aura.web.Router", "handle", "Router.java", 10)
        });
        Router router = new Router();
        router.get("/vault", ctx -> {
            throw failure;
        });

        TestApplications.client(config, router).get("/vault").execute();

        String json = Files.readString(Files.list(tempDir).findFirst().orElseThrow());
        assertThat(json)
                .doesNotContain("com.acme.private", "secretpath", "VaultService")
                .contains("<redacted-frame>", "<redacted-path>")
                .contains("io.aura.web.Router");
    }

    @Test
    void stackTextRedactsSecretsPathsAndNonFrameworkFrames() {
        SnapshotRedactor redactor = new SnapshotRedactor(new SnapshotOptions());
        String stack = "java.lang.RuntimeException\n"
                + "\\tat com.acme.private.Class.run(/Users/foo/secretpath/config.yml:1)\n"
                + "\\tat java.base/java.lang.Thread.run(Thread.java:840)\n"
                + "Caused by: com.acme.private.RootCause.fail\n";

        String result = redactor.stackText(stack);

        assertThat(result)
                .doesNotContain("com.acme.private", "secretpath", "config.yml")
                .contains("<redacted-frame>", "<redacted-path>", "java.base/java.lang.Thread.run");
        assertThat(redactor.stackText(result)).isEqualTo(result);
    }

    @Test
    void stackTextRedactsWindowsPaths() {
        SnapshotRedactor redactor = new SnapshotRedactor(new SnapshotOptions());
        String result = redactor.stackText("\\tat com.acme.Loader.load(C:\\aura\\secretpath\\Loader.java:7)");
        assertThat(result).doesNotContain("secretpath", "com.acme")
                .contains("<redacted-path>", "<redacted-frame>");
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
