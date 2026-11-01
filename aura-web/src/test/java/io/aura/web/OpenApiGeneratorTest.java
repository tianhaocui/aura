package io.aura.web;

import io.aura.contract.ApiParameter;
import io.aura.contract.Route;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiGeneratorTest {
    @Test
    void generatesOpenApiFromSharedRouteContract() {
        Route route = Route.of("GET", "/orders/{id}", (BaseHandler) ctx -> { })
                .describedAs("Get order")
                .withReturnType("Order")
                .withParams(List.of(new ApiParameter("id", "long", "path", true, "Order ID")));

        Map<String, Object> document = OpenApiGenerator.generate("Orders", "1.0.0", List.of(route));
        assertThat(document).containsEntry("openapi", "3.0.3");
        Map<?, ?> paths = (Map<?, ?>) document.get("paths");
        Map<?, ?> operation = (Map<?, ?>) ((Map<?, ?>) paths.get("/orders/{id}")).get("get");
        assertThat(operation.get("operationId")).isEqualTo("get_orders_id");
        List<?> parameters = (List<?>) operation.get("parameters");
        assertThat(parameters).hasSize(1);
    }

    @Test
    void generatesResponseSchemaFromSharedRouteContract() {
        Route route = Route.of("GET", "/orders/1", (BaseHandler) ctx -> { })
                .describedAs("Get order")
                .withReturnType("Order")
                .withResponseSchema(Map.of("type", "object",
                        "properties", Map.of("id", Map.of("type", "integer"))));

        Map<String, Object> document = OpenApiGenerator.generate("Orders", "1.0.0", List.of(route));
        Map<?, ?> paths = (Map<?, ?>) document.get("paths");
        Map<?, ?> operation = (Map<?, ?>) ((Map<?, ?>) paths.get("/orders/1")).get("get");
        Map<?, ?> response = (Map<?, ?>) ((Map<?, ?>) operation.get("responses")).get("200");
        assertThat(response.containsKey("content")).isTrue();
    }
}
