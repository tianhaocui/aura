# aura-ai

`aura-ai` 是 Aura 的可选 AI 客户端模块。它不绑定具体模型厂商，应用只依赖稳定的 `AiProvider` SPI，便于 AI 生成代码时只学习一套调用方式。

作为 Aura 模块使用：

```java
AuraApp.create()
        .use(AiModule.create(provider))
        .start();
```

业务模块通过 `context.require(AiClient.class)` 获取客户端；模块停止时客户端会自动关闭。

```java
AiClient client = new AiClient(provider);
AiRequest request = AiRequest.builder()
        .systemPrompt("Return concise JSON")
        .prompt("Summarize this order")
        .model("my-model")
        .timeout(Duration.ofSeconds(20))
        .budget(AiBudget.builder()
                .maxInputCharacters(8_000)
                .maxOutputCharacters(4_000)
                .maxOutputTokens(2_000)
                .maxCostMicros(50_000)
                .build())
        .build();

AiResponse response = client.chat(request);
OrderSummary summary = StructuredOutput.parse(response, OrderSummary.class);
```

## 设计边界

- `AiProvider` 负责具体 HTTP/API 协议；`AiClient` 负责统一超时、取消和同步/流式调用。
- `AiRequest` 是不可变请求对象，参数、模型、预算和脱敏策略集中在一个 Builder 中。
- 取消是协作式的；provider 应在网络读取和 token 回调之间检查 `request.cancellationToken()`。
- `StructuredOutput` 使用安全 JSON 解析，不执行 AutoType；业务校验通过 `parseAndValidate` 注入。
- `ConversationStore`、`EmbeddingProvider` 和 `Retriever` 只定义扩展契约，不引入数据库、向量库或具体云厂商依赖。

## 流式输出

```java
client.stream(request, new AiStreamHandler() {
    public void onToken(String token) { sse.send("token", token); }
    public void onComplete(AiResponse response) { sse.send("done", ""); }
    public void onError(Throwable error) { sse.close(); }
});
```

Provider 可通过 `ServiceLoader` 注册，应用也可以直接传入 provider 实例，便于 TestClient 风格的内存测试。

## OpenAI-compatible provider

`aura-ai` includes an optional provider-neutral HTTP adapter for services that implement the
OpenAI chat-completions wire format. It uses JDK `HttpClient`; no vendor SDK is required:

```java
AiProvider provider = OpenAiCompatibleProvider.create(
        "https://api.example.com/v1/chat/completions",
        System.getenv("AI_API_KEY"),
        "my-model");
AuraApp.create()
        .use(AiModule.create(provider))
        .start();
```

The adapter sends redacted prompts, applies request timeouts, caps response bytes, supports SSE
`data:` chunks, and reports provider failures with `AiProviderException.code()`. Keep the endpoint
and key in deployment configuration; they are never included in provider error messages.

Typed tool calling is available without provider-specific request maps:

```java
AiRequest request = AiRequest.builder()
        .prompt("Find order 42")
        .tool(new AiToolDefinition("lookup_order", "Look up an order",
                Map.of("type", "object")))
        .toolChoice("auto")
        .build();
AiResponse response = client.chat(request);
for (AiToolCall call : response.toolCalls()) {
    // Dispatch the call through the application's selected tool/MCP adapter.
}
```

The provider parses JSON arguments but does not execute tools implicitly. Authorization and tool
dispatch remain explicit application/module responsibilities.

Transient synchronous provider failures can be retried with a bounded policy. The timeout is the
total budget across all attempts:

```java
AiRequest request = AiRequest.builder()
        .prompt("Summarize order 42")
        .timeout(Duration.ofSeconds(20))
        .retry(AiRetryPolicy.fixed(3, Duration.ofMillis(200)))
        .build();
```

Only HTTP 429/5xx and I/O failures are retried. Streaming calls are not retried automatically,
because replaying partial tokens would produce ambiguous output.
