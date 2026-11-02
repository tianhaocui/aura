package io.aura.web;

import io.aura.port.BaseHandler;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.aura.contract.ApiParameter;
import io.aura.contract.Route;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiGeneratorTest {
    @Test
    void generatesOpenApiFromSharedRouteContract() {
        Route route = Route.of("GET", "/orders/{id}", (BaseHandler) ctx -> { })
                .describedAs("Get order")
                .withReturnType("Order")
                .withParams(List.of(new ApiParameter("id", "long", "path", true, "Order ID")));

        JSONObject document = SchemaWire.document(OpenApiGenerator.generate("Orders", "1.0.0", List.of(route)));
        assertThat(document.getString("openapi")).isEqualTo("3.0.3");
        JSONObject operation = SchemaWire.operation(document, "/orders/{id}", "GET");
        assertThat(operation.getString("operationId")).isEqualTo("get_orders_id");
        assertThat(operation.getJSONArray("parameters")).hasSize(1);
    }

    @Test
    void generatesResponseSchemaFromSharedRouteContract() {
        Route route = Route.of("GET", "/orders/1", (BaseHandler) ctx -> { })
                .describedAs("Get order")
                .withReturnType("Order")
                .withResponseSchema(java.util.Map.of("type", "object",
                        "properties", java.util.Map.of("id", java.util.Map.of("type", "integer"))));

        JSONObject document = SchemaWire.document(OpenApiGenerator.generate("Orders", "1.0.0", List.of(route)));
        JSONObject response = SchemaWire.operation(document, "/orders/1", "GET")
                .getJSONObject("responses").getJSONObject("200");
        assertThat(response.containsKey("content"))
                .as("a route that describes its response publishes it, or a client codes against an empty body")
                .isTrue();
    }

    enum Channel { SMS, EMAIL }

    @Test
    void queryParameterTypesComeFromTheOneScalarTable() {
        Route route = Route.of("GET", "/report", (BaseHandler) ctx -> { })
                .withParams(List.of(
                        new ApiParameter("total", "BigDecimal", "query", false, null),
                        new ApiParameter("rank", "short", "query", false, null),
                        new ApiParameter("id", "Long", "query", true, null)));

        // The private table this replaces called BigDecimal and short strings; the schema
        // document and the MCP tool said number and integer for the same handler.
        assertThat(parameterTypes(route, "/report")).containsExactly("number", "integer", "integer");
    }

    @Test
    void declaredParameterSchemaReachesOpenApiInsteadOfAnUnboundedString() {
        Route route = Route.of("GET", "/notify", (BaseHandler) ctx -> { })
                .withParams(List.of(new ApiParameter("channel", "Channel", "query", true, null,
                        TypeSchema.describe(Channel.class))));

        assertThat(parameterSchema(route, "/notify", "channel"))
                .containsEntry("type", "string")
                .containsEntry("enum", List.of("SMS", "EMAIL"));
    }

    /** The JSON types OpenAPI published for the route's parameters, in declaration order. */
    private static List<String> parameterTypes(Route route, String path) {
        return listedParameters(route, path).toJavaList(JSONObject.class).stream()
                .map(parameter -> parameter.getJSONObject("schema").getString("type"))
                .toList();
    }

    private static JSONObject parameterSchema(Route route, String path, String name) {
        return SchemaWire.parameter(listedParameters(route, path), name).getJSONObject("schema");
    }

    private static JSONArray listedParameters(Route route, String path) {
        return SchemaWire.operation(SchemaWire.document(
                OpenApiGenerator.generate("Orders", "1.0.0", List.of(route))), path, "GET")
                .getJSONArray("parameters");
    }
}
