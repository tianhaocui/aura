---
name: aura
description: "Aura 1.0 Java backend development with explicit capability and business modules, TestClient verification, and shared route contracts."
---

# Aura 1.0 AI Coding Contract

Use this skill when a Maven project depends on Aura or the user asks for Aura
framework code.

## Red Lines

1. Stability first.
2. Then optimize for AI accuracy, speed, low error rate, verifiability, low
   token use, and easy learning.
3. Use exactly one application style: `AuraApp.create().use(...)`.
4. Keep every read, queue, executor, replay buffer, and key registry bounded.
5. Verify generated code with `TestClient` before starting a real server.

## Minimal Pattern

Import `io.github.tianhaocui:aura-bom` in `dependencyManagement`, then depend on
`io.github.tianhaocui:aura-starter` without a version. Optional Aura capability
modules also omit versions. Do not generate independently versioned Aura
dependencies.

```java
public final class OrdersModule implements AuraModule {
    @Override public ModuleMetadata descriptor() {
        return ModuleMetadata.of("orders", "1.0.0");
    }

    @Override public void install(ModuleContext context) {
        context.route(new RouteContribution(
                "GET", "/orders/{id}",
                (BaseHandler) request -> request.json(find(request.pathLong("id"))),
                "Get an order"));
    }
}

AuraApp app = AuraApp.create(new AuraConfig().port(8080))
        .use(HealthModule.create())
        .use(new OrdersModule());
```

Capability modules expose one `create(...)` entry point. Business modules
declare requirements in `ModuleMetadata` and retrieve them with
`context.require(Type.class)`. Never use global lookups or implicit plugin
activation.

## Configuration

Use `AuraConfig` for runtime settings before `AuraApp.create(config)`. Use a
record bound with `context.config(RecordType.class)` for module settings under
`aura.<module-id>.*`. Invalid configuration must fail startup.

## Routes And Errors

- Path parameters use `{id}`.
- Use `bodyOrThrow` for required validated JSON bodies.
- Use `ApiException`/`CodedException` for expected structured errors.
- Add descriptions and parameter metadata needed by schema/OpenAPI/MCP.
- Never expose raw exception messages or stacks in production responses.

## Verification

```java
AuraApp app = AuraApp.create().use(new OrdersModule()).prepare();
TestClient client = TestClient.of(app);

client.post("/orders").body(request).expect(201);
client.get("/orders/1").expect(200);
client.schema();
client.openApi();
```

Run the narrow test first, then `mvn test`, `mvn -DskipTests package`,
`bash scripts/verify-aura-contracts.sh`, and `git diff --check`.

## Capability Guides

Read only the guide needed for the task:

- `docs/aura-core.md`
- `docs/aura-web.md`
- `docs/aura-db.md`
- `docs/aura-workflow.md`
- `docs/aura-ai.md`
- `docs/aura-mcp.md`
- `docs/aura-processor.md`
