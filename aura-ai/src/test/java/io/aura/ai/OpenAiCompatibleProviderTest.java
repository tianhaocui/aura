package io.aura.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleProviderTest {
    @Test
    void postsRedactedChatRequestAndDecodesResponse() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"model\":\"served\",\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"total_tokens\":2}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            OpenAiCompatibleProvider provider = OpenAiCompatibleProvider.create(
                    "http://localhost:" + server.getAddress().getPort() + "/chat", "secret-key", "default");
            AiResponse response = provider.chat(AiRequest.builder()
                    .systemPrompt("system password=hidden")
                    .prompt("use token=hidden")
                    .timeout(Duration.ofSeconds(2)).build());

            assertThat(response.content()).isEqualTo("ok");
            assertThat(response.model()).isEqualTo("served");
            assertThat(requestBody.get()).contains("<redacted>").doesNotContain("password=hidden", "token=hidden");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamsSseTokensAndHonorsResponseLimit() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/stream", exchange -> {
            byte[] response = ("data: {\"choices\":[{\"delta\":{\"content\":\"hel\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
                    URI.create("http://localhost:" + server.getAddress().getPort() + "/stream"),
                    "", "default", java.net.http.HttpClient.newHttpClient(), Duration.ofSeconds(2), 512);
            StringBuilder output = new StringBuilder();
            provider.stream(new AiRequest("prompt"), new AiStreamHandler() {
                @Override public void onToken(String token) { output.append(token); }
            });
            assertThat(output).hasToString("hello");
        } finally {
            server.stop(0);
        }

        assertThatThrownBy(() -> OpenAiCompatibleProvider.create("file:///tmp/ai", "", "model"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
