package io.aura.mcp;

import io.aura.contract.ApiParameter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class McpUtilTest {

    // --- buildToolName ---

    @Test
    void buildToolName_getWithIdParam() {
        assertThat(McpUtil.buildToolName("GET", "/user/{id}")).isEqualTo("get_user_by_id");
    }

    @Test
    void buildToolName_getCollection() {
        assertThat(McpUtil.buildToolName("GET", "/user")).isEqualTo("get_user");
    }

    @Test
    void buildToolName_post() {
        assertThat(McpUtil.buildToolName("POST", "/user")).isEqualTo("post_user");
    }

    @Test
    void buildToolName_deleteWithIdParam() {
        assertThat(McpUtil.buildToolName("DELETE", "/user/{id}")).isEqualTo("delete_user_by_id");
    }

    @Test
    void buildToolName_rootPath() {
        assertThat(McpUtil.buildToolName("GET", "/")).isEqualTo("get_root");
    }

    // --- jsonType ---

    @Test
    void jsonType_int_returnsInteger() {
        assertThat(McpUtil.jsonType("int")).isEqualTo("integer");
    }

    @Test
    void jsonType_String_returnsString() {
        assertThat(McpUtil.jsonType("String")).isEqualTo("string");
    }

    @Test
    void jsonType_boolean_returnsBoolean() {
        assertThat(McpUtil.jsonType("boolean")).isEqualTo("boolean");
    }

    @Test
    void jsonType_double_returnsNumber() {
        assertThat(McpUtil.jsonType("double")).isEqualTo("number");
    }

    @Test
    void jsonType_null_returnsString() {
        assertThat(McpUtil.jsonType(null)).isEqualTo("string");
    }

    @Test
    void jsonType_unknownType_returnsString() {
        assertThat(McpUtil.jsonType("SomeCustomClass")).isEqualTo("string");
    }

    // --- inputSchema ---

    @Test
    void inputSchema_emptyList_emptyProperties() {
        McpWire.Schema schema = McpWire.of(McpUtil.inputSchema(List.of())).asSchema();
        assertThat(schema.type()).isEqualTo("object");
        assertThat(schema.propertyNames()).isEmpty();
        assertThat(schema.keys()).doesNotContain("required");
    }

    @Test
    void inputSchema_withParams_correctStructure() {
        List<McpParam> params = List.of(
                new McpParam(new ApiParameter("id", "int", "query", true, "the item id")),
                new McpParam(new ApiParameter("name", "String", "query", true, null))
        );
        McpWire.Schema schema = McpWire.of(McpUtil.inputSchema(params)).asSchema();

        assertThat(schema.type()).isEqualTo("object");
        assertThat(schema.propertyNames()).contains("id", "name");
        assertThat(schema.property("id").type()).isEqualTo("integer");
        assertThat(schema.property("id").description()).isEqualTo("the item id");
        assertThat(schema.property("name").type()).isEqualTo("string");
        assertThat(schema.property("name").keys()).doesNotContain("description");
        assertThat(schema.required()).containsExactly("id", "name");
    }

    @Test
    void inputSchema_preservesOptionalParameters() {
        List<McpParam> params = List.of(
                new McpParam(new ApiParameter("query", "String", "query", true, null)),
                new McpParam(new ApiParameter("page", "int", "query", false, null)));

        assertThat(McpWire.of(McpUtil.inputSchema(params)).asSchema().required())
                .containsExactly("query");
    }

    @Test
    void inputSchema_preservesStructuredBodySchema() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("type", "object");
        body.put("properties", Map.of("name", Map.of("type", "string")));
        List<McpParam> params = List.of(new McpParam(
                new ApiParameter("request", "CreateRequest", "body", true, null, body)));

        McpWire.Property request = McpWire.of(McpUtil.inputSchema(params)).asSchema()
                .property("request");
        assertThat(request.type()).isEqualTo("object");
        assertThat(request.nested("properties").keys()).contains("name");
    }

    @Test
    void inputSchema_projectsTheEnumChoicesItsContractDeclares() {
        List<McpParam> params = List.of(new McpParam(
                new ApiParameter("status", "OrderStatus", "query", true, "Order status",
                        Map.of("type", "string", "enum", List.of("PAID", "NEW")))));

        McpWire.Property status = McpWire.of(McpUtil.inputSchema(params)).asSchema()
                .property("status");
        assertThat(status.enumValues()).containsExactly("PAID", "NEW");
        assertThat(status.description()).isEqualTo("Order status");
    }

    @Test
    void inputSchema_refusesAParameterItWouldHaveToCallAString() {
        // jsonType() has no entry for an unknown type name and defaults to "string", which is how
        // a hand-declared record parameter used to reach the model as a string it could not send.
        assertThatThrownBy(() -> new McpParam(
                new ApiParameter("request", "CreateRequest", "body", true, null)))
                .isInstanceOf(io.aura.contract.ContractException.class)
                .hasMessageContaining("'request' is declared as CreateRequest");
    }
}
