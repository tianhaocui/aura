package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.contract.Route;
import io.aura.port.BaseHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StreamingDownloadTest {

    @TempDir Path directory;

    @Test
    void aPathDownloadStreamsTheFileWithItsLengthAndSanitisedName() throws Exception {
        Path source = directory.resolve("large.bin");
        byte[] block = new byte[8192];
        for (int i = 0; i < block.length; i++) block[i] = (byte) (i & 0xff);
        try (OutputStream output = Files.newOutputStream(source)) {
            for (int i = 0; i < 1024; i++) output.write(block);
        }
        long size = Files.size(source);
        Path downloaded = directory.resolve("downloaded.bin");
        int port = freePort();

        AuraApp app = TestApplications.app(new AuraConfig().port(port), "stream-path",
                Route.of("GET", "/large", (BaseHandler) ctx ->
                        ctx.sendFile("big \"file\".bin", source, "application/octet-stream")));
        app.start();
        try {
            HttpResponse<Path> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/large"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofFile(downloaded));

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Length"))
                    .hasValue(Long.toString(size));
            assertThat(response.headers().firstValue("Content-Disposition"))
                    .hasValue("attachment; filename=\"big file.bin\"");
            assertThat(Files.mismatch(source, downloaded)).isEqualTo(-1L);
        } finally {
            app.stop();
        }
    }

    @Test
    void aGeneratedDownloadUsesChunkedTransferWhenTheLengthIsUnknown() throws Exception {
        int blockSize = 64 * 1024;
        int total = 3 * 1024 * 1024;
        byte[] block = new byte[blockSize];
        for (int i = 0; i < block.length; i++) block[i] = (byte) ('a' + (i % 26));
        int port = freePort();

        AuraApp app = TestApplications.app(new AuraConfig().port(port), "stream-generated",
                Route.of("GET", "/generated", (BaseHandler) ctx ->
                        ctx.sendStream("generated.bin", -1, "application/octet-stream", output -> {
                            for (int written = 0; written < total; written += block.length) {
                                output.write(block, 0, Math.min(block.length, total - written));
                            }
                        })));
        app.start();
        try {
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/generated"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).hasSize(total);
            assertThat(response.headers().firstValue("Content-Length")).isEmpty();
        } finally {
            app.stop();
        }
    }

    @Test
    void aGeneratedDownloadKeepsItsDeclaredLength() throws Exception {
        byte[] body = "streamed-body".getBytes(StandardCharsets.UTF_8);
        int port = freePort();

        AuraApp app = TestApplications.app(new AuraConfig().port(port), "stream-length",
                Route.of("GET", "/known", (BaseHandler) ctx ->
                        ctx.sendStream("known.txt", body.length, "text/plain", output ->
                                output.write(body))));
        app.start();
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/known"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("streamed-body");
            assertThat(response.headers().firstValue("Content-Length"))
                    .hasValue(Integer.toString(body.length));
        } finally {
            app.stop();
        }
    }

    @Test
    void aMissingPathIsRejectedBeforeTheResponseStarts() {
        var ctx = new MockContext(Map.of(), Map.of(), Map.of(), null, TestApplications.runtime());

        assertThatThrownBy(() -> ctx.sendFile("missing.bin", directory.resolve("missing.bin")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ctx.isResponseStarted()).isFalse();
        assertThat(ctx.fileBytes).isNull();

        assertThatThrownBy(() -> ctx.sendFile("directory.bin", directory))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ctx.isResponseStarted()).isFalse();
    }

    @Test
    void theInMemoryAdapterCapturesPathAndGeneratedDownloads() throws Exception {
        Path source = directory.resolve("report.bin");
        byte[] data = "report-data".getBytes(StandardCharsets.UTF_8);
        Files.write(source, data);
        var pathCtx = new MockContext(Map.of(), Map.of(), Map.of(), null, TestApplications.runtime());

        pathCtx.sendFile("report.bin", source, "application/octet-stream");

        assertThat(pathCtx.fileBytes).isEqualTo(data);
        assertThat(pathCtx.fileLength).isEqualTo(data.length);
        assertThat(pathCtx.responseHeaders().get("Content-Disposition"))
                .isEqualTo("attachment; filename=\"report.bin\"");

        var streamCtx = new MockContext(Map.of(), Map.of(), Map.of(), null, TestApplications.runtime());

        streamCtx.sendStream("generated.bin", -1, "application/octet-stream",
                output -> output.write("generated".getBytes(StandardCharsets.UTF_8)));

        assertThat(streamCtx.fileBytes).isEqualTo("generated".getBytes(StandardCharsets.UTF_8));
        assertThat(streamCtx.fileLength).isEqualTo(-1);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
