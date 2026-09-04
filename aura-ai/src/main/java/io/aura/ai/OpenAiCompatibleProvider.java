package io.aura.ai;

import com.alibaba.fastjson2.JSON;
import io.aura.contract.CancellationToken;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Provider for APIs implementing the OpenAI chat-completions wire format.
 * The endpoint is supplied by the application so this class does not choose a vendor.
 */
public final class OpenAiCompatibleProvider implements AiProvider {
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 1024 * 1024;
    private final URI endpoint;
    private final String apiKey;
    private final String defaultModel;
    private final HttpClient client;
    private final Duration defaultTimeout;
    private final int maxResponseBytes;

    public OpenAiCompatibleProvider(URI endpoint, String apiKey, String defaultModel) {
        this(endpoint, apiKey, defaultModel, HttpClient.newHttpClient(), Duration.ofSeconds(60),
                DEFAULT_MAX_RESPONSE_BYTES);
    }

    public OpenAiCompatibleProvider(URI endpoint, String apiKey, String defaultModel,
                                    HttpClient client, Duration defaultTimeout, int maxResponseBytes) {
        this.endpoint = validateEndpoint(endpoint);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.defaultModel = requireText(defaultModel, "defaultModel");
        this.client = Objects.requireNonNull(client, "client");
        this.defaultTimeout = validateTimeout(defaultTimeout);
        if (maxResponseBytes < 1) throw new IllegalArgumentException("maxResponseBytes must be positive");
        this.maxResponseBytes = maxResponseBytes;
    }

    public static OpenAiCompatibleProvider create(String endpoint, String apiKey, String defaultModel) {
        try { return new OpenAiCompatibleProvider(URI.create(endpoint), apiKey, defaultModel); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("Invalid AI provider endpoint", error); }
    }

    @Override public String name() { return "openai-compatible"; }

    @Override
    public AiResponse chat(AiRequest request) throws Exception {
        Objects.requireNonNull(request, "request");
        request.cancellationToken().throwIfCancelled();
        HttpRequest httpRequest = request(request, false);
        HttpResponse<InputStream> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            String payload = readLimited(body, maxResponseBytes, request.cancellationToken());
            if (response.statusCode() / 100 != 2) throw providerError(response.statusCode());
            return decode(payload, request.model());
        }
    }

    @Override
    public void stream(AiRequest request, AiStreamHandler handler) throws Exception {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(handler, "handler");
        request.cancellationToken().throwIfCancelled();
        HttpResponse<InputStream> response = client.send(request(request, true), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            try (InputStream ignored = response.body()) { throw providerError(response.statusCode()); }
        }
        handler.onStart();
        StringBuilder content = new StringBuilder();
        long[] bytes = {0L};
        try (InputStream body = response.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                request.cancellationToken().throwIfCancelled();
                bytes[0] += line.getBytes(StandardCharsets.UTF_8).length + 1L;
                if (bytes[0] > maxResponseBytes) {
                    throw new AiProviderException("AI_RESPONSE_TOO_LARGE", response.statusCode(),
                            "AI streaming response exceeds configured limit");
                }
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) break;
                String token = decodeDelta(data);
                if (token != null && !token.isEmpty()) {
                    content.append(token);
                    handler.onToken(token);
                }
            }
        }
        request.cancellationToken().throwIfCancelled();
        handler.onComplete(new AiResponse(content.toString(), model(request), Map.of(), null));
    }

    private HttpRequest request(AiRequest request, boolean stream) {
        Map<String, Object> payload = new LinkedHashMap<>();
        List<Map<String, String>> messages = new ArrayList<>();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(Map.of("role", "system", "content", request.redactedSystemPrompt()));
        }
        messages.add(Map.of("role", "user", "content", request.redactedPrompt()));
        payload.put("model", model(request));
        payload.put("messages", messages);
        request.parameters().forEach((name, value) -> {
            if (!"model".equals(name) && !"messages".equals(name) && !"stream".equals(name)) {
                payload.put(name, value);
            }
        });
        if (stream) payload.put("stream", true);
        if (!request.tools().isEmpty()) {
            payload.put("tools", request.tools().stream().map(tool -> Map.of(
                    "type", "function",
                    "function", Map.of("name", tool.name(), "description",
                            tool.description() == null ? "" : tool.description(),
                            "parameters", tool.inputSchema()))).toList());
        }
        if (request.toolChoice() != null && !request.toolChoice().isBlank()) {
            payload.put("tool_choice", request.toolChoice());
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .timeout(effectiveTimeout(request));
        if (!apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
        return builder.POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(payload), StandardCharsets.UTF_8)).build();
    }

    @SuppressWarnings("unchecked")
    private AiResponse decode(String payload, String requestedModel) throws AiProviderException {
        try {
            Map<String, Object> root = JSON.parseObject(payload, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) root.get("choices");
            if (choices == null || choices.isEmpty()) throw new IllegalArgumentException("choices is empty");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            Object content = message == null ? null : message.get("content");
            if (content == null) content = choices.get(0).get("text");
            List<AiToolCall> toolCalls = parseToolCalls(message);
            if (content == null && toolCalls.isEmpty()) throw new IllegalArgumentException("response content is missing");
            Object usage = root.get("usage");
            return new AiResponse(content == null ? "" : content.toString(), modelValue(root, requestedModel),
                    usage instanceof Map<?, ?> map ? (Map<String, ?>) map : Map.of(), root, toolCalls);
        } catch (RuntimeException error) {
            throw new AiProviderException("AI_RESPONSE_INVALID", 200, "AI provider returned invalid response");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<AiToolCall> parseToolCalls(Map<String, Object> message) throws AiProviderException {
        if (message == null || !(message.get("tool_calls") instanceof List<?> rawCalls)) return List.of();
        List<AiToolCall> calls = new ArrayList<>();
        try {
            for (Object raw : rawCalls) {
                if (!(raw instanceof Map<?, ?> call)) continue;
                String id = call.get("id") == null ? null : call.get("id").toString();
                Map<String, Object> function = (Map<String, Object>) call.get("function");
                if (function == null || function.get("name") == null) continue;
                Object rawArguments = function.get("arguments");
                Map<String, Object> arguments = rawArguments instanceof Map<?, ?> map
                        ? (Map<String, Object>) map
                        : rawArguments == null ? Map.of() : JSON.parseObject(rawArguments.toString(), Map.class);
                calls.add(new AiToolCall(id, function.get("name").toString(), arguments));
            }
            return List.copyOf(calls);
        } catch (RuntimeException error) {
            throw new AiProviderException("AI_TOOL_CALL_INVALID", 200,
                    "AI provider returned invalid tool call arguments");
        }
    }

    @SuppressWarnings("unchecked")
    private static String decodeDelta(String payload) throws AiProviderException {
        try {
            Map<String, Object> root = JSON.parseObject(payload, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) root.get("choices");
            if (choices == null || choices.isEmpty()) return null;
            Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
            Object content = delta == null ? null : delta.get("content");
            return content == null ? null : content.toString();
        } catch (RuntimeException error) {
            throw new AiProviderException("AI_STREAM_INVALID", 200, "AI provider returned invalid stream data");
        }
    }

    private String model(AiRequest request) { return request.model() == null || request.model().isBlank() ? defaultModel : request.model(); }

    private static String modelValue(Map<String, Object> root, String requested) {
        Object model = root.get("model");
        return model == null ? requested : model.toString();
    }

    private Duration effectiveTimeout(AiRequest request) {
        return request.timeout() == null || request.timeout().isZero() ? defaultTimeout : request.timeout();
    }

    private AiProviderException providerError(int status) {
        return new AiProviderException("AI_PROVIDER_HTTP_" + status, status,
                "AI provider request failed with HTTP " + status);
    }

    private static String readLimited(InputStream input, int limit, CancellationToken token) throws Exception {
        byte[] buffer = new byte[8192];
        long total = 0L;
        java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream(Math.min(limit, 8192));
        int read;
        while ((read = input.read(buffer)) != -1) {
            token.throwIfCancelled();
            total += read;
            if (total > limit) throw new AiProviderException("AI_RESPONSE_TOO_LARGE", 200,
                    "AI response exceeds configured limit");
            result.write(buffer, 0, read);
        }
        return result.toString(StandardCharsets.UTF_8);
    }

    private static URI validateEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (!("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null) {
            throw new IllegalArgumentException("AI provider endpoint must be an absolute HTTP(S) URI");
        }
        return endpoint;
    }

    private static Duration validateTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero()) return Duration.ofSeconds(60);
        if (timeout.isNegative()) throw new IllegalArgumentException("defaultTimeout must not be negative");
        return timeout;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
