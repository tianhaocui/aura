package io.aura.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class McpToolBuilderTest {

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private static McpToolBuilder builder(String name, String desc) {
        McpRouter router = new McpRouter();
        return new McpToolBuilder(router, name, desc);
    }

    // -----------------------------------------------------------------------
    // build() — basic structure
    // -----------------------------------------------------------------------

    @Test
    void build_noParams_returnsToolWithEmptyParams() {
        McpTool tool = builder("my_tool", "does stuff").build();
        assertThat(tool.name()).isEqualTo("my_tool");
        assertThat(tool.description()).isEqualTo("does stuff");
        assertThat(tool.params()).isEmpty();
        assertThat(tool.handler()).isNull();
    }

    @Test
    void build_withStringParam_registersCorrectType() {
        McpTool tool = builder("t", "d")
                .param("name", String.class, "the name")
                .build();
        assertThat(tool.params()).hasSize(1);
        McpParam p = tool.params().get(0);
        assertThat(p.name()).isEqualTo("name");
        assertThat(p.type()).isEqualTo("string");
        assertThat(p.description()).isEqualTo("the name");
        assertThat(p.isEnum()).isFalse();
    }

    @Test
    void build_withIntParam_registersIntegerType() {
        McpTool tool = builder("t", "d")
                .param("count", int.class, "count")
                .build();
        assertThat(tool.params().get(0).type()).isEqualTo("integer");
    }

    @Test
    void build_withLongParam_registersIntegerType() {
        McpTool tool = builder("t", "d")
                .param("ts", long.class, "timestamp")
                .build();
        assertThat(tool.params().get(0).type()).isEqualTo("integer");
    }

    @Test
    void build_withBooleanParam_registersBooleanType() {
        McpTool tool = builder("t", "d")
                .param("active", boolean.class, "is active")
                .build();
        assertThat(tool.params().get(0).type()).isEqualTo("boolean");
    }

    @Test
    void build_multipleParams_preservesOrder() {
        McpTool tool = builder("t", "d")
                .param("a", String.class, "first")
                .param("b", int.class, "second")
                .param("c", boolean.class, "third")
                .build();
        assertThat(tool.params()).hasSize(3);
        assertThat(tool.params().get(0).name()).isEqualTo("a");
        assertThat(tool.params().get(1).name()).isEqualTo("b");
        assertThat(tool.params().get(2).name()).isEqualTo("c");
    }

    // -----------------------------------------------------------------------
    // param() with mapping
    // -----------------------------------------------------------------------

    @Test
    void param_withMapping_registersEnumValues() {
        McpToolBuilder b = builder("t", "d")
                .param("city", String.class, "城市", Map.of("北京", "010", "上海", "021"));
        McpTool tool = b.build();

        McpParam p = tool.params().get(0);
        assertThat(p.isEnum()).isTrue();
        assertThat(p.enumValues()).hasSize(2);
    }

    @Test
    void param_withMapping_populatesMappingsMap() {
        McpToolBuilder b = builder("t", "d")
                .param("city", String.class, "城市", Map.of("北京", "010", "上海", "021"));

        Map<String, Map<String, Object>> mappings = b.mappings();
        assertThat(mappings).containsKey("city");
        assertThat(mappings.get("city")).containsEntry("北京", "010");
        assertThat(mappings.get("city")).containsEntry("上海", "021");
    }

    @Test
    void param_withIntMapping_storesIntValues() {
        McpToolBuilder b = builder("t", "d")
                .param("zone", int.class, "区号", Map.of("北京", 10, "上海", 21));

        Map<String, Map<String, Object>> mappings = b.mappings();
        assertThat(mappings.get("zone")).containsEntry("北京", 10);
    }

    @Test
    void param_withMapping_enumValuesContainKeyAndValue() {
        McpToolBuilder b = builder("t", "d")
                .param("city", String.class, "城市", Map.of("北京", "010"));
        McpTool tool = b.build();

        McpEnumValue ev = tool.params().get(0).enumValues().get(0);
        assertThat(ev.label()).isEqualTo("北京");
        assertThat(ev.code()).isEqualTo("010");
    }

    @Test
    void param_multipleMappings_allStoredSeparately() {
        McpToolBuilder b = builder("t", "d")
                .param("city", String.class, "城市", Map.of("北京", "010"))
                .param("zone", int.class, "区号", Map.of("A", 1));

        Map<String, Map<String, Object>> mappings = b.mappings();
        assertThat(mappings).containsKeys("city", "zone");
    }

    // -----------------------------------------------------------------------
    // param() with enum type
    // -----------------------------------------------------------------------

    enum Status { ACTIVE, INACTIVE, PENDING }

    @Test
    void param_enumType_registersEnumParam() {
        McpTool tool = builder("t", "d")
                .param("status", Status.class, "状态")
                .build();

        McpParam p = tool.params().get(0);
        assertThat(p.isEnum()).isTrue();
        assertThat(p.type()).isEqualTo("string");
        assertThat(p.enumValues()).hasSize(3);
    }

    @Test
    void param_enumType_enumValuesUseConstantNames() {
        McpTool tool = builder("t", "d")
                .param("status", Status.class, "状态")
                .build();

        List<String> labels = tool.params().get(0).enumValues()
                .stream().map(McpEnumValue::label).toList();
        assertThat(labels).containsExactly("ACTIVE", "INACTIVE", "PENDING");
    }

    enum LabeledStatus {
        OPEN("开放"), CLOSED("关闭");
        final String label;
        LabeledStatus(String label) { this.label = label; }
    }

    @Test
    void param_enumWithLabelField_includesLabelInDescription() {
        McpTool tool = builder("t", "d")
                .param("status", LabeledStatus.class, "状态")
                .build();

        List<String> labels = tool.params().get(0).enumValues()
                .stream().map(McpEnumValue::label).toList();
        // label field present → "OPEN(开放)", "CLOSED(关闭)"
        assertThat(labels.get(0)).contains("OPEN").contains("开放");
        assertThat(labels.get(1)).contains("CLOSED").contains("关闭");
    }

    interface Labeled { String label(); }

    enum LabeledMethod implements Labeled {
        YES { public String label() { return "是"; } },
        NO  { public String label() { return "否"; } };
    }

    @Test
    void param_enumWithLabelMethod_includesLabelInDescription() {
        McpTool tool = builder("t", "d")
                .param("flag", LabeledMethod.class, "标志")
                .build();

        List<String> labels = tool.params().get(0).enumValues()
                .stream().map(McpEnumValue::label).toList();
        assertThat(labels.get(0)).contains("YES").contains("是");
        assertThat(labels.get(1)).contains("NO").contains("否");
    }

    // -----------------------------------------------------------------------
    // handler() — registers tool in router
    // -----------------------------------------------------------------------

    @Test
    void handler_registersToolInRouter() {
        McpRouter router = new McpRouter();
        new McpToolBuilder(router, "greet", "打招呼")
                .param("name", String.class, "名字")
                .handler(ctx -> "hello " + ctx.getString("name"));

        assertThat(router.tools()).hasSize(1);
        assertThat(router.tools().get(0).name()).isEqualTo("greet");
    }

    @Test
    void handler_toolIsInvocable() throws Exception {
        McpRouter router = new McpRouter();
        new McpToolBuilder(router, "add", "加法")
                .param("a", int.class, "A")
                .param("b", int.class, "B")
                .handler(ctx -> ctx.getInt("a") + ctx.getInt("b"));

        Object result = router.invoke("add", Map.of("a", "3", "b", "4"));
        assertThat(result).isEqualTo(7);
    }

    @Test
    void handler_returnsBuilderForChaining() {
        McpRouter router = new McpRouter();
        McpToolBuilder b = new McpToolBuilder(router, "t", "d");
        McpToolBuilder returned = b.handler(ctx -> null);
        assertThat(returned).isSameAs(b);
    }

    // -----------------------------------------------------------------------
    // build() returns immutable params list
    // -----------------------------------------------------------------------

    @Test
    void build_paramsListIsImmutable() {
        McpTool tool = builder("t", "d")
                .param("x", String.class, "x")
                .build();
        assertThatThrownBy(() -> tool.params().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
