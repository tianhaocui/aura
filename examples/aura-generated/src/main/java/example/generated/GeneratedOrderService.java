package example.generated;

import io.aura.annotation.Desc;
import io.aura.annotation.Get;
import io.aura.annotation.Path;
import io.aura.annotation.Post;

import java.util.LinkedHashMap;
import java.util.Map;

@Path("/generated/orders")
@Desc("Generated order API")
public final class GeneratedOrderService {
    private final Map<Long, Order> orders = new LinkedHashMap<>();

    public GeneratedOrderService() {
        orders.put(1L, new Order(1L, "ready"));
    }

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

    public record CreateOrder(long id, String status) { }
    public record Order(long id, String status) { }
}
