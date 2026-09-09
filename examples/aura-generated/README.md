# Generated Aura Example

This example compiles `GeneratedOrderServiceAuraRoutes` through the optional
`aura-processor`, installs the generated contributor with `context.route(...)`,
and verifies it through the same `TestClient` contract used by Undertow.

Run it from the repository root:

```bash
mvn -pl examples/aura-generated -am test
```
