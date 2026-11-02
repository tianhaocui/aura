# Aura Processor

`aura-processor` is an optional JDK annotation processor for production route
generation. It does not change `aura-core` dependencies or application startup.

For a public service method annotated with `@Get`, `@Post`, `@Put`, `@Delete`,
`@Patch`, `@Head`, or `@Options`, the processor generates a same-package
`<ServiceName>AuraRoutes` class with a `routes()` method that returns `Route`
values.

The generated class contains direct Java calls, explicit route metadata,
and fixed path/query/body binding. Install it explicitly:

```java
new OrderServiceAuraRoutes(orderService).routes().forEach(context::route);
```

The source it reads is an ordinary service -- public methods, public class, scalar
path and query parameters, a record body with its validation annotations -- and that
source is what has to stay true as the annotations change:

<!-- aura:compile name=processor-annotated-service -->
```java
package docs.example;

import io.aura.annotation.Desc;
import io.aura.annotation.Get;
import io.aura.annotation.NotBlank;
import io.aura.annotation.Path;
import io.aura.annotation.Post;

import java.util.LinkedHashMap;
import java.util.Map;

@Path("/generated/orders")
@Desc("Generated order API")
public final class GeneratedOrderService {
    private final Map<Long, Order> orders = new LinkedHashMap<>();

    @Get("/{id}")
    @Desc("Get one generated order")
    public Order get(@Desc("Order identifier") long id) {
        return orders.get(id);
    }

    @Post("")
    @Desc("Create generated order")
    public Order create(CreateOrder request) {
        Order order = new Order(request.id(), request.status());
        orders.put(order.id(), order);
        return order;
    }

    public record CreateOrder(long id, @NotBlank String status) { }
    public record Order(long id, String status) { }
}
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
