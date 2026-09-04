package io.aura.web;

import io.aura.contract.ApiParameter;
import io.aura.contract.ApiRoute;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiGeneratorTest {
    @Test
    void generatesOpenApiFromSharedRouteContract() {
        ApiRoute route = new ApiRoute("GET", "/orders/{id}", "Get order", "Order",
                List.of(new ApiParameter("id", "long", "path", true, "Order ID")), "curl");

        Map<String, Object> document = OpenApiGenerator.generate("Orders", "1.0.0", List.of(route));
        assertThat(document).containsEntry("openapi", "3.0.3");
        Map<?, ?> paths = (Map<?, ?>) document.get("paths");
        Map<?, ?> operation = (Map<?, ?>) ((Map<?, ?>) paths.get("/orders/{id}")).get("get");
        assertThat(operation.get("operationId")).isEqualTo("get_orders_id");
        List<?> parameters = (List<?>) operation.get("parameters");
        assertThat(parameters).hasSize(1);
    }
}
