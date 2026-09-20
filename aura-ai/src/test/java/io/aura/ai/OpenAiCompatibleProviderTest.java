package io.aura.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
                    .parameter("metadata", Map.of(
                            "authorization", "Bearer parameter-secret",
                            "nested", List.of("client_secret=inside-list")))
                    .tool(new AiToolDefinition("safe_tool",
                            "Authorization: Bearer description-secret",
                            Map.of("type", "object", "default", "api_key=schema-secret")))
                    .timeout(Duration.ofSeconds(2)).build());

            assertThat(response.content()).isEqualTo("ok");
            assertThat(response.model()).isEqualTo("served");
            assertThat(requestBody.get()).contains("<redacted>").doesNotContain(
                    "password=hidden", "token=hidden", "parameter-secret",
                    "inside-list", "description-secret", "schema-secret");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsProviderNeutralStructuredOutputToOpenAiJsonSchema() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/structured", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"{\\\"answer\\\":\\\"ok\\\"}\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            StructuredOutputSpec spec = StructuredOutputSpec.strict("answer", Map.of(
                    "type", "object",
                    "properties", Map.of("answer", Map.of("type", "string")),
                    "required", List.of("answer"),
                    "additionalProperties", false));
            OpenAiCompatibleProvider provider = OpenAiCompatibleProvider.create(
                    "http://localhost:" + server.getAddress().getPort() + "/structured", "", "default");
            try (AiClient client = new AiClient(provider)) {
                assertThat(client.chat(AiRequest.builder().prompt("answer")
                        .structuredOutput(spec).build()).content()).contains("answer");
            }
            JSONObject payload = JSON.parseObject(requestBody.get());
            assertThat(payload.getJSONObject("response_format").getString("type"))
                    .isEqualTo("json_schema");
            assertThat(payload.getJSONObject("response_format").getJSONObject("json_schema"))
                    .containsEntry("name", "answer")
                    .containsEntry("strict", true)
                    .containsKey("schema");
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
        assertThatThrownBy(() -> OpenAiCompatibleProvider.create("https://example.test/api#fragment", "", "model"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OpenAiCompatibleProvider(
                URI.create("https://example.test/api"), "", "model",
                java.net.http.HttpClient.newBuilder()
                        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build(),
                Duration.ofSeconds(2), 512))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void streamsAndAggregatesMultipleToolCallsAcrossSseChunks() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/stream-tools", exchange -> {
            Map<String, Object> first = Map.of("choices", List.of(Map.of("delta", Map.of(
                    "tool_calls", List.of(
                            Map.of("index", 0, "id", "call-1", "function",
                                    Map.of("name", "look")),
                            Map.of("index", 1, "id", "call-2", "function",
                                    Map.of("name", "notify")))))));
            Map<String, Object> second = Map.of("choices", List.of(Map.of("delta", Map.of(
                    "tool_calls", List.of(
                            Map.of("index", 0, "function", Map.of("name", "up",
                                    "arguments", JSON.toJSONString(Map.of("id", 42)))),
                            Map.of("index", 1, "function", Map.of("arguments",
                                    JSON.toJSONString(Map.of("text", "hello")))))))));
            byte[] response = ("data: " + JSON.toJSONString(first) + "\n\n"
                    + "data: " + JSON.toJSONString(second) + "\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            OpenAiCompatibleProvider provider = OpenAiCompatibleProvider.create(
                    "http://localhost:" + server.getAddress().getPort() + "/stream-tools", "", "default");
            AtomicReference<AiResponse> completed = new AtomicReference<>();
            provider.stream(new AiRequest("call tools"), new AiStreamHandler() {
                @Override public void onToken(String token) { }
                @Override public void onComplete(AiResponse response) { completed.set(response); }
            });
            assertThat(completed.get().content()).isEmpty();
            assertThat(completed.get().toolCalls()).extracting(AiToolCall::name)
                    .containsExactly("lookup", "notify");
            assertThat(completed.get().toolCalls().get(0).arguments()).containsEntry("id", 42);
            assertThat(completed.get().toolCalls().get(1).arguments()).containsEntry("text", "hello");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void malformedStreamingToolArgumentsFailWithStableCode() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/invalid-stream-tool", exchange -> {
            Map<String, Object> event = Map.of("choices", List.of(Map.of("delta", Map.of(
                    "tool_calls", List.of(Map.of("index", 0, "id", "call-invalid",
                            "function", Map.of("name", "lookup", "arguments", "{bad")))))));
            byte[] response = ("data: " + JSON.toJSONString(event) + "\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            OpenAiCompatibleProvider provider = OpenAiCompatibleProvider.create(
                    "http://localhost:" + server.getAddress().getPort() + "/invalid-stream-tool",
                    "", "default");
            assertThatThrownBy(() -> provider.stream(new AiRequest("call tool"), token -> { }))
                    .isInstanceOf(AiProviderException.class)
                    .extracting("code").isEqualTo("AI_TOOL_CALL_INVALID");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamingBudgetRequestsAndParsesUsage() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/usage-stream", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                    + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            OpenAiCompatibleProvider provider = OpenAiCompatibleProvider.create(
                    "http://localhost:" + server.getAddress().getPort() + "/usage-stream", "", "default");
            AiRequest request = AiRequest.builder().prompt("prompt")
                    .budget(AiBudget.builder().maxTotalTokens(3).build()).build();
            try (AiClient client = new AiClient(provider)) {
                StringBuilder output = new StringBuilder();
                client.stream(request, output::append);
                assertThat(output).hasToString("ok");
            }
            assertThat(requestBody.get()).contains("stream_options", "include_usage");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsTypedToolsAndParsesToolCallsWithoutExposingRawErrors() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/tools", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Map<String, Object> function = new java.util.LinkedHashMap<>();
            function.put("name", "lookup_order");
            function.put("arguments", JSON.toJSONString(Map.of("id", 42)));
            Map<String, Object> call = new java.util.LinkedHashMap<>();
            call.put("id", "call-1");
            call.put("function", function);
            Map<String, Object> message = new java.util.LinkedHashMap<>();
            message.put("content", null);
            message.put("tool_calls", List.of(call));
            byte[] response = JSON.toJSONString(Map.of(
                    "choices", List.of(Map.of("message", message))))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            OpenAiCompatibleProvider provider = OpenAiCompatibleProvider.create(
                    "http://localhost:" + server.getAddress().getPort() + "/tools", "", "default");
            AiResponse response = provider.chat(AiRequest.builder().prompt("find order")
                    .tool(new AiToolDefinition("lookup_order", "Look up an order",
                            java.util.Map.of("type", "object", "properties", java.util.Map.of(
                                    "id", java.util.Map.of("type", "integer")))))
                    .toolChoice("auto").build());

            assertThat(response.content()).isEmpty();
            assertThat(response.toolCalls()).singleElement().satisfies(call -> {
                assertThat(call.name()).isEqualTo("lookup_order");
                assertThat(call.arguments()).containsEntry("id", 42);
            });
            assertThat(requestBody.get()).contains("lookup_order", "tool_choice", "parameters");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aiClientRetriesTransientProviderFailuresWithinTheTotalTimeout() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/retry", exchange -> {
            if (calls.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"recovered\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            OpenAiCompatibleProvider provider = OpenAiCompatibleProvider.create(
                    "http://localhost:" + server.getAddress().getPort() + "/retry", "", "default");
            try (AiClient client = new AiClient(provider)) {
                AiResponse response = client.chat(AiRequest.builder().prompt("retry")
                        .timeout(Duration.ofSeconds(2))
                        .retry(AiRetryPolicy.fixed(2, Duration.ZERO))
                        .build());
                assertThat(response.content()).isEqualTo("recovered");
            }
            assertThat(calls).hasValue(2);
        } finally {
            server.stop(0);
        }
    }
}
