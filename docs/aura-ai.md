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
AiUsageTotals window = client.usageTotals();
```

For cross-process accounting, pass an optional `AiUsageSink`; the sink can write
to a database, queue, or metrics service without making any of those systems a
core dependency:

```java
AiClient client = new AiClient(provider, executor, event ->
        accountingStore.append(event.completedAt(), event.model(), event.usage()));
```

Sink failures are isolated from completed provider responses. The event is
emitted after provider usage has been normalized or conservatively priced, and
includes the request model, input-character count, usage values, and whether
the provider reported usable accounting data.

`maxInputCharacters` is a preflight limit over the complete provider-neutral
request: user and system prompts, model/tool choice, parameter JSON, tool names,
descriptions and schemas, plus the structured-output schema. For redacted fields,
Aura freezes both the raw and redacted representations, takes the larger size, then
rejects overages before invoking the provider. Provider-specific JSON envelope
overhead is not included.

Request provider-constrained JSON and enforce the same schema locally:

```java
StructuredOutputSpec output = StructuredOutputSpec.strict("order_summary", Map.of(
        "type", "object",
        "properties", Map.of("orderId", Map.of("type", "string")),
        "required", List.of("orderId"),
        "additionalProperties", false));
AiResponse response = client.chat(AiRequest.builder()
        .prompt("Summarize the order")
        .structuredOutput(output)
        .build());
```

OpenAI-compatible providers map this to `response_format.json_schema`.
`AiClient` always validates the returned JSON locally for every provider and
fails with `AI_STRUCTURED_OUTPUT_INVALID` on malformed or non-conforming output.
Schema declarations are checked eagerly against Aura's supported JSON Schema
subset. Non-recursive local `$defs`/`definitions` references must resolve;
recursive references, unknown keywords, and incorrectly typed constraints fail
at startup instead of drifting between provider and local validation semantics.

When a provider reports token counts but not cost, supply explicit model pricing
instead of relying on a hidden default:

```java
AiRequest request = AiRequest.builder()
        .prompt("Summarize this order")
        .pricing(AiPricing.of(250, 1_000)) // micro-units per million tokens
        .budget(AiBudget.builder().maxCostMicros(20_000).build())
        .build();
```

Pricing is opt-in and rounded up conservatively; Aura never guesses a vendor's
rates. Reported provider cost always takes precedence over the estimate.

## 设计边界

- `AiProvider` 负责具体 HTTP/API 协议；`AiClient` 负责统一超时、取消和同步/流式调用。
- `AiRequest` 是不可变请求对象，参数、模型、预算和脱敏策略集中在一个 Builder 中。
- 取消是协作式的；provider 应在网络读取和 token 回调之间检查 `request.cancellationToken()`。
- `StructuredOutput` 使用安全 JSON 解析，不执行 AutoType；业务校验通过 `parseAndValidate` 注入。
- `ConversationStore`、`EmbeddingProvider` 和 `Retriever` 只定义扩展契约，不引入数据库、向量库或具体云厂商依赖。
- `ConversationStore` 的 append/load 使用不可变 versioned snapshot；`InMemoryConversationStore` 提供有上限的参考实现。持久化适配器必须原子执行 bounds 和 expected-version 检查，不能把会话变成无界字符串列表。

```java
ConversationStore conversations = new InMemoryConversationStore(32, 24_000);
ConversationStore.ConversationSnapshot before = conversations.load("order-42");
ConversationStore.ConversationSnapshot after = conversations.append(
        "order-42", new ConversationStore.AiMessage("user", "Is this delayed?"),
        before.version());
```

Appending with a stale non-negative version fails with
`ConversationConflictException`; passing `-1` uses an atomic unconditional append.
The reference store evicts the oldest messages when aggregate bounds are reached
and rejects a single message that cannot fit.
- `RagPipeline` 提供唯一推荐的 bounded retrieval-to-context 组合；检索数量和 context 字符数都必须有上限。

```java
RagPipeline rag = new RagPipeline(retriever, 8, 12_000);
AiRequest request = rag.request("Which orders are delayed?")
        .model("my-model")
        .timeout(Duration.ofSeconds(20))
        .budget(AiBudget.builder().maxInputCharacters(16_000).build())
        .build();
AiResponse answer = client.chat(request);
```

`RagPipeline` 不执行隐式 tool call、不持久化会话、不绑定向量数据库；Embedding、Retriever 和
ConversationStore 仍由能力模块通过 SPI 提供。大结果会在边界处确定性截断，不允许无界 context
进入 AI 请求预算。`request(query)` 是推荐的组合入口：固定的 system prompt 明确把检索内容视为
不可信数据，passages 与 question 使用 JSON 编码，检索内容不能伪造边界。返回的
`RagRequestBuilder` 只开放 model、timeout、budget、parameters、tools、retry、pricing、structured
output 和 cancellation；它不允许替换 prompt、system prompt 或默认 redactor。不要手工拼接
`context.text()`。
- `AiClient.usageTotals()` exposes cumulative request/token/cost counters for one
  accounting window. Call `resetUsage()` at the start of a new window; unknown
  provider usage is counted separately instead of being guessed.

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
The default redactor covers Bearer/Basic authorization values and recursively
redacts sensitive fields inside request parameters, tool descriptions, and tool
schemas before provider serialization.
OpenAI-compatible streaming aggregates incremental `tool_calls` by index; names
and JSON arguments may span multiple SSE chunks. The completed `AiResponse`
contains the same typed `AiToolCall` model as synchronous calls. Malformed
arguments fail with `AI_TOOL_CALL_INVALID` rather than producing a partial call.

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

Only HTTP 429/5xx and I/O failures are retried. A streaming failure is retried
only before the provider emits any token callback; after the first callback,
Aura fails without replaying visible output. The timeout is shared by every
attempt and cancellation-aware backoff.

Streaming providers must return only after calling exactly one of `onComplete`
or `onError`. Duplicate terminals, tokens after a terminal, completion followed
by a thrown failure, and returning without a terminal fail with
`AI_PROVIDER_STREAM_CONTRACT`. Aura emits `onStart` once and exactly one terminal
callback to application code across all attempts after startup succeeds. If the
bounded executor rejects startup, Aura reports only `onError`.
Callbacks are serialized. A timeout returns without waiting for an application
callback that ignores interruption; the pending `onError` is delivered after
that callback returns. A callback that never returns cannot receive a later
terminal callback, so handlers must keep callback work bounded.

`AiClient.executorMetrics()` reports worker limit, active/queued work, queue
capacity, completed tasks, rejections, and timeouts for the default bounded
executor. A caller-supplied executor is marked opaque while its rejection and
timeout counters remain available.
