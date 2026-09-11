package io.aura.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpOutputTest {
    @Test
    @SuppressWarnings("unchecked")
    void explicitContentPreservesMcpTypesAndStructuredData() throws Exception {
        McpRouter router = new McpRouter();
        router.tool("outputs", "Return explicit MCP content")
                .handler(ctx -> new McpImage("aGVsbG8=", "image/png"));
        Map<String, Object> imageResult = McpContent.result(router.invoke("outputs", Map.of()));
        java.util.List<Map<String, Object>> image = (java.util.List<Map<String, Object>>) imageResult.get("content");
        assertThat(image.get(0))
                .containsEntry("type", "image")
                .containsEntry("mimeType", "image/png");

        Map<String, Object> structured = McpContent.result(
                new McpStructured(Map.of("ok", true)));
        assertThat(structured).containsKey("structuredContent");
        assertThat((Map<String, Object>) structured.get("structuredContent")).containsEntry("ok", true);
    }

    @Test
    void validatesImageAndResourceInputs() {
        assertThatThrownBy(() -> new McpImage("", "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpImage("data", "text/plain"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpResourceLink("", "text/plain", "body"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
