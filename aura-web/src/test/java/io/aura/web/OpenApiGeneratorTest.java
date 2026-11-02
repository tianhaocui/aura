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

    enum Channel { SMS, EMAIL }

    @Test
    @SuppressWarnings("unchecked")
    void queryParameterTypesComeFromTheOneScalarTable() {
        Route route = Route.of("GET", "/report", (BaseHandler) ctx -> { })
                .withParams(List.of(
                        new ApiParameter("total", "BigDecimal", "query", false, null),
                        new ApiParameter("rank", "short", "query", false, null),
                        new ApiParameter("id", "Long", "query", true, null)));

        // The private table this replaces called BigDecimal and short strings; the schema
        // document and the MCP tool said number and integer for the same handler.
        List<Map<String, Object>> parameters =
                (List<Map<String, Object>>) operation(route, "/report").get("parameters");
        assertThat(parameters).extracting(parameter -> ((Map<String, Object>) parameter.get("schema")).get("type"))
                .containsExactly("number", "integer", "integer");
    }

    @Test
    @SuppressWarnings("unchecked")
    void declaredParameterSchemaReachesOpenApiInsteadOfAnUnboundedString() {
        Route route = Route.of("GET", "/notify", (BaseHandler) ctx -> { })
                .withParams(List.of(new ApiParameter("channel", "Channel", "query", true, null,
                        TypeSchema.describe(Channel.class))));

        List<Map<String, Object>> parameters =
                (List<Map<String, Object>>) operation(route, "/notify").get("parameters");
        assertThat(parameters.get(0).get("schema")).isInstanceOfSatisfying(Map.class, schema ->
                assertThat(schema).containsEntry("type", "string")
                        .containsEntry("enum", List.of("SMS", "EMAIL")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> operation(Route route, String path) {
        Map<String, Object> document = OpenApiGenerator.generate("Orders", "1.0.0", List.of(route));
        Map<String, Object> pathItem = (Map<String, Object>) ((Map<String, Object>) document.get("paths")).get(path);
        return (Map<String, Object>) pathItem.get("get");
    }
}
