# aura-ai

`aura-ai` 是 Aura 的可选 AI 客户端模块。它不绑定具体模型厂商，应用只依赖稳定的 `AiProvider` SPI，便于 AI 生成代码时只学习一套调用方式。

```java
AiClient client = new AiClient(provider);
AiRequest request = AiRequest.builder()
        .systemPrompt("Return concise JSON")
        .prompt("Summarize this order")
        .model("my-model")
        .timeout(Duration.ofSeconds(20))
        .budget(new AiBudget(8_000, 4_000, 0))
        .redactor(AiRedactor.none())
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
