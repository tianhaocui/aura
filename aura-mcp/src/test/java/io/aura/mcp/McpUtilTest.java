package io.aura.mcp;

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

    // --- buildInputSchema ---

    @Test
    void buildInputSchema_emptyList_emptyProperties() {
        Map<String, Object> schema = McpUtil.buildInputSchema(List.of());
        assertThat(schema).containsEntry("type", "object");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertThat(props).isEmpty();
        assertThat(schema).doesNotContainKey("required");
    }

    @Test
    void buildInputSchema_withParams_correctStructure() {
        List<McpUtil.ParamInfo> params = List.of(
                new McpUtil.ParamInfo("id", "int", "the item id"),
                new McpUtil.ParamInfo("name", "String", null)
        );
        Map<String, Object> schema = McpUtil.buildInputSchema(params);

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
}
