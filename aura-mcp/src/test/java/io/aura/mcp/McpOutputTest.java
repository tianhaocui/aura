package io.aura.mcp;

import io.aura.contract.Route;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpOutputTest {
    @Test
    void explicitContentPreservesMcpTypesAndStructuredData() throws Exception {
        McpRouter router = new McpRouter();
        router.tool(Route.of("POST", "/outputs",
                (McpHandler) ctx -> new McpImage("aGVsbG8=", "image/png"))
                .describedAs("Return explicit MCP content"));
        McpWire image = McpWire.of(McpContent.result(router.invoke("post_outputs", Map.of())));
        assertThat(image.contentBlocks()).singleElement().satisfies(block -> {
            assertThat(block.type()).isEqualTo("image");
            assertThat(block.string("mimeType")).isEqualTo("image/png");
        });

        McpWire structured = McpWire.of(McpContent.result(new McpStructured(Map.of("ok", true))));
        assertThat(structured.keys()).contains("structuredContent");
        assertThat(structured.object("structuredContent").flag("ok")).isTrue();
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
