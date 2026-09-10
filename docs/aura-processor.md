# Aura Processor

`aura-processor` is an optional JDK annotation processor for production route
generation. It does not change `aura-core` dependencies or application startup.

For a public service method annotated with `@Get`, `@Post`, `@Put`, `@Delete`,
`@Patch`, `@Head`, or `@Options`, the processor generates a same-package
`<ServiceName>AuraRoutes` class implementing `RouteContributor`.

The generated contributor contains direct Java calls, explicit route metadata,
and fixed path/query/body binding. Install it explicitly:

```java
new OrderServiceAuraRoutes(orderService).routes().forEach(context::route);
```

Do not also register the same service through the reflection-based service
registrar; that would contribute duplicate routes and fail startup validation.

The first generator scope intentionally supports public annotated methods with:

- `BaseContext` or `Context` parameters;
- path/query scalar parameters (`String`, numeric primitives/wrappers, boolean);
- record/POJO body parameters via `bodyOrThrow`;
- `void` or JSON-serializable return values.

For record body components, the generated schema includes the supported
validation annotations `@NotBlank`, `@NotNull`, `@Min`, `@Max`, `@Size`, and
`@Pattern`. Runtime `BeanValidator` remains authoritative; generated metadata
is descriptive and lets AI clients construct valid payloads before sending a
request.

Unsupported or complex binding shapes should remain on the reflection prototype
path until a generated binding rule is added with a contract test.
