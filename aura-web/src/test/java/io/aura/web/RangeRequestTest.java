package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.contract.Route;
import io.aura.port.BaseHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class RangeRequestTest {

    private static final int SIZE = 1000;

    @TempDir Path directory;

    @Test
    void aPathDownloadServesTheRequestedSlice() throws Exception {
        Path source = directory.resolve("payload.bin");
        Files.write(source, payload());
        int port = freePort();

        withApp(new AuraConfig().port(port), "range-path", () -> {
            HttpResponse<byte[]> response = get(port, "/file", "bytes=100-199");

            assertThat(response.statusCode()).isEqualTo(206);
            assertThat(response.headers().firstValue("Accept-Ranges")).hasValue("bytes");
            assertThat(response.headers().firstValue("Content-Range"))
                    .hasValue("bytes 100-199/" + SIZE);
            assertThat(response.headers().firstValue("Content-Length")).hasValue("100");
            assertThat(response.body()).isEqualTo(Arrays.copyOfRange(payload(), 100, 200));
        }, Route.of("GET", "/file", (BaseHandler) ctx ->
                ctx.sendFile("payload.bin", source, "application/octet-stream")));
    }

    @Test
    void openEndedAndSuffixRangesSelectAHeadAndATail() throws Exception {
        Path source = directory.resolve("payload.bin");
        Files.write(source, payload());
        int port = freePort();

        withApp(new AuraConfig().port(port), "range-forms", () -> {
            HttpResponse<byte[]> tail = get(port, "/file", "bytes=-10");
            assertThat(tail.statusCode()).isEqualTo(206);
            assertThat(tail.headers().firstValue("Content-Range"))
                    .hasValue("bytes " + (SIZE - 10) + "-" + (SIZE - 1) + "/" + SIZE);
            assertThat(tail.body()).isEqualTo(Arrays.copyOfRange(payload(), SIZE - 10, SIZE));

            HttpResponse<byte[]> head = get(port, "/file", "bytes=990-");
            assertThat(head.statusCode()).isEqualTo(206);
            assertThat(head.headers().firstValue("Content-Range"))
                    .hasValue("bytes 990-" + (SIZE - 1) + "/" + SIZE);
            assertThat(head.body()).isEqualTo(Arrays.copyOfRange(payload(), 990, SIZE));
        }, Route.of("GET", "/file", (BaseHandler) ctx ->
                ctx.sendFile("payload.bin", source, "application/octet-stream")));
    }

    @Test
    void anUnsatisfiableRangeIsAnsweredWith416AndTheFullLength() throws Exception {
        Path source = directory.resolve("payload.bin");
        Files.write(source, payload());
        int port = freePort();

        withApp(new AuraConfig().port(port), "range-416", () -> {
            HttpResponse<byte[]> response = get(port, "/file", "bytes=5000-6000");

            assertThat(response.statusCode()).isEqualTo(416);
            assertThat(response.headers().firstValue("Accept-Ranges")).hasValue("bytes");
            assertThat(response.headers().firstValue("Content-Range"))
                    .hasValue("bytes */" + SIZE);
            assertThat(response.body()).isEmpty();
        }, Route.of("GET", "/file", (BaseHandler) ctx ->
                ctx.sendFile("payload.bin", source, "application/octet-stream")));
    }

    @Test
    void multipleRangesFallBackToTheWholeRepresentation() throws Exception {
        Path source = directory.resolve("payload.bin");
        Files.write(source, payload());
        int port = freePort();

        withApp(new AuraConfig().port(port), "range-multiple", () -> {
            HttpResponse<byte[]> response = get(port, "/file", "bytes=0-9,20-29");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Range")).isEmpty();
            assertThat(response.body()).isEqualTo(payload());
        }, Route.of("GET", "/file", (BaseHandler) ctx ->
                ctx.sendFile("payload.bin", source, "application/octet-stream")));
    }

    @Test
    void byteArrayAndDeclaredLengthStreamsHonourRanges() throws Exception {
        byte[] data = payload();
        int port = freePort();

        withApp(new AuraConfig().port(port), "range-adapters", () -> {
            HttpResponse<byte[]> fromArray = get(port, "/bytes", "bytes=10-19");
            assertThat(fromArray.statusCode()).isEqualTo(206);
            assertThat(fromArray.body()).isEqualTo(Arrays.copyOfRange(data, 10, 20));

            HttpResponse<byte[]> fromStream = get(port, "/known", "bytes=900-999");
            assertThat(fromStream.statusCode()).isEqualTo(206);
            assertThat(fromStream.headers().firstValue("Content-Range"))
                    .hasValue("bytes 900-999/" + SIZE);
            assertThat(fromStream.body()).isEqualTo(Arrays.copyOfRange(data, 900, SIZE));
        },
                Route.of("GET", "/bytes", (BaseHandler) ctx ->
                        ctx.sendFile("bytes.bin", data, "application/octet-stream")),
                Route.of("GET", "/known", (BaseHandler) ctx ->
                        ctx.sendStream("known.bin", data.length, "application/octet-stream",
                                output -> output.write(data))));
    }

    @Test
    void aChunkedStreamCannotBeRangedAndSaysSo() throws Exception {
        byte[] data = payload();
        int port = freePort();

        withApp(new AuraConfig().port(port), "range-chunked", () -> {
            HttpResponse<byte[]> response = get(port, "/chunked", "bytes=0-9");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Accept-Ranges")).hasValue("none");
            assertThat(response.headers().firstValue("Content-Range")).isEmpty();
            assertThat(response.body()).isEqualTo(data);
        }, Route.of("GET", "/chunked", (BaseHandler) ctx ->
                ctx.sendStream("chunked.bin", -1, "application/octet-stream",
                        output -> output.write(data))));
    }

    @Test
    void aRangeRequestIsNotCompressedAndSlicesTheRawBytes() throws Exception {
        byte[] text = "0123456789abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);
        int port = freePort();

        withApp(new AuraConfig().port(port).gzip(true).gzipMinSize(0), "range-gzip", () -> {
            HttpResponse<byte[]> ranged = get(port, "/text", "bytes=0-9",
                    "Accept-Encoding", "gzip");
            assertThat(ranged.statusCode()).isEqualTo(206);
            assertThat(ranged.headers().firstValue("Content-Encoding")).isEmpty();
            assertThat(ranged.headers().firstValue("Content-Range"))
                    .hasValue("bytes 0-9/" + text.length);
            assertThat(ranged.body()).isEqualTo(Arrays.copyOfRange(text, 0, 10));

            HttpResponse<byte[]> whole = get(port, "/text", null,
                    "Accept-Encoding", "gzip");
            assertThat(whole.statusCode()).isEqualTo(200);
            assertThat(whole.headers().firstValue("Content-Encoding")).hasValue("gzip");
            assertThat(gunzip(whole.body())).isEqualTo(text);
        }, Route.of("GET", "/text", (BaseHandler) ctx ->
                ctx.sendFile("text.txt", text, "text/plain")));
    }

    @Test
    void staticResourcesHonourRangesToo() throws Exception {
        int port = freePort();
        byte[] file = "<html><body>Hello Static</body></html>\n".getBytes(StandardCharsets.UTF_8);

        withApp(new AuraConfig().port(port).staticFiles("/public"), "range-static", () -> {
            HttpResponse<byte[]> response = get(port, "/index.html", "bytes=0-9");

            assertThat(response.statusCode()).isEqualTo(206);
            assertThat(response.headers().firstValue("Content-Range"))
                    .hasValue("bytes 0-9/" + file.length);
            assertThat(response.body()).isEqualTo(Arrays.copyOfRange(file, 0, 10));
        });
    }

    private static byte[] payload() {
        byte[] data = new byte[SIZE];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xff);
        return data;
    }

    private static byte[] gunzip(byte[] compressed) throws Exception {
        try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return input.readAllBytes();
        }
    }

    private static HttpResponse<byte[]> get(int port, String path, String range) throws Exception {
        return get(port, path, range, null, null);
    }

    private static HttpResponse<byte[]> get(int port, String path, String range,
                                            String header, String value) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .GET();
        if (range != null) request.header("Range", range);
        if (header != null) request.header(header, value);
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static void withApp(AuraConfig config, String name,
                                ThrowingRunnable assertions, Route... routes) throws Exception {
        AuraApp app = TestApplications.app(config, name, routes);
        app.start();
        try {
            assertions.run();
        } finally {
            app.stop();
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
