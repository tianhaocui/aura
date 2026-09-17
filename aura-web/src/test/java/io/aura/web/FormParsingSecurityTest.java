package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.contract.RouteContribution;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class FormParsingSecurityTest {

    @Test
    void parsesUrlEncodedFormsWithExplicitParserConfiguration() throws Exception {
        int port = freePort();
        AuraApp app = TestApplications.app(new AuraConfig().port(port), "form-parser",
                new RouteContribution("POST", "/form",
                        (BaseHandler) ctx -> ctx.text(ctx.formField("name"))));
        app.start();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/form"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("name=Aura"))
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("Aura");
        } finally {
            app.stop();
        }
    }

    @Test
    void rejectsMultipartFilesAboveConfiguredUploadLimit() throws Exception {
        int port = freePort();
        String boundary = "aura-security-boundary";
        byte[] body = multipart(boundary, "upload", "payload.txt", "12345");
        AuraConfig config = new AuraConfig().port(port).env("prod")
                .maxUploadSize(4).maxBodySize(1024 * 1024);
        AuraApp app = TestApplications.app(config, "upload-limit",
                new RouteContribution("POST", "/upload",
                        (BaseHandler) ctx -> ctx.text(String.valueOf(ctx.file("upload").size()))));
        app.start();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/upload"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body()).contains("Internal Server Error");
        } finally {
            app.stop();
        }
    }

    private static byte[] multipart(String boundary, String field, String filename, String content) {
        String value = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + content + "\r\n"
                + "--" + boundary + "--\r\n";
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
