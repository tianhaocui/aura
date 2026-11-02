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
        Map<String, Object> schema = McpUtil.inputSchema(List.of());
        assertThat(schema).containsEntry("type", "object");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertThat(props).isEmpty();
        assertThat(schema).doesNotContainKey("required");
    }

    @Test
    void inputSchema_withParams_correctStructure() {
        List<McpParam> params = List.of(
                new McpParam(new ApiParameter("id", "int", "tool", true, "the item id"), List.of()),
                new McpParam(new ApiParameter("name", "String", "tool", true, null), List.of())
        );
        Map<String, Object> schema = McpUtil.inputSchema(params);

        assertThat(schema).containsEntry("type", "object");

        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertThat(props).containsKey("id");
        assertThat(props).containsKey("name");

        @SuppressWarnings("unchecked")
        Map<String, Object> idProp = (Map<String, Object>) props.get("id");
        assertThat(idProp).containsEntry("type", "integer");
        assertThat(idProp).containsEntry("description", "the item id");

        @SuppressWarnings("unchecked")
        Map<String, Object> nameProp = (Map<String, Object>) props.get("name");
        assertThat(nameProp).containsEntry("type", "string");
        assertThat(nameProp).doesNotContainKey("description");

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertThat(required).containsExactly("id", "name");
    }

    @Test
    void inputSchema_preservesOptionalParameters() {
        List<McpParam> params = List.of(
                new McpParam(new ApiParameter("query", "String", "query", true, null), List.of()),
                new McpParam(new ApiParameter("page", "int", "query", false, null), List.of()));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) McpUtil.inputSchema(params).get("required");
        assertThat(required).containsExactly("query");
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputSchema_preservesStructuredBodySchema() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("type", "object");
        body.put("properties", Map.of("name", Map.of("type", "string")));
        List<McpParam> params = List.of(new McpParam(
                new ApiParameter("request", "CreateRequest", "body", true, null, body), List.of()));

        Map<String, Object> properties = (Map<String, Object>) McpUtil.inputSchema(params).get("properties");
        Map<String, Object> request = (Map<String, Object>) properties.get("request");
        assertThat(request).containsEntry("type", "object");
        assertThat((Map<String, Object>) request.get("properties")).containsKey("name");
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputSchema_declaresEnumLabelsTheModelCanEmit() {
        List<McpParam> params = List.of(new McpParam(
                new ApiParameter("status", "String", "tool", true, "Order status"),
                List.of(new McpEnumValue("PAID(已支付)", "PAID"),
                        new McpEnumValue("NEW(新建)", "NEW"))));

        Map<String, Object> properties = (Map<String, Object>) McpUtil.inputSchema(params).get("properties");
        Map<String, Object> status = (Map<String, Object>) properties.get("status");
        assertThat(status).containsEntry("enum", List.of("PAID(已支付)", "NEW(新建)"));
        assertThat(status.get("description")).isEqualTo("Order status: PAID(已支付), NEW(新建)");
    }
}
