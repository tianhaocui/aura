package io.aura.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpContentTest {

    @Test
    void stringResult_preservesTextOnlyEnvelope() {
        Map<String, Object> result = McpContent.result("hello");
        assertThat(result).containsKey("content").doesNotContainKey("structuredContent");
    }

    @Test
    void objectResult_exposesStructuredContentAndTextCompatibility() {
        Map<String, Object> result = McpContent.result(Map.of("ok", true));
        assertThat(result).containsKey("content").containsKey("structuredContent");
        assertThat(result.get("structuredContent")).isEqualTo(Map.of("ok", true));
    }
}
