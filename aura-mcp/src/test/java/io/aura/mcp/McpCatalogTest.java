package io.aura.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpCatalogTest {

    @Test
    void resources_areListedAndReadable() {
        McpRouter router = new McpRouter()
                .resource("aura://guide", "Guide", "A guide", "text/plain", "hello");

        assertThat(router.listResources().get("resources")).asList().first()
                .isEqualTo(Map.of("uri", "aura://guide", "name", "Guide",
                        "description", "A guide", "mimeType", "text/plain"));
        assertThat(router.readResource("aura://guide").get("contents")).asList().first()
                .isEqualTo(Map.of("uri", "aura://guide", "mimeType", "text/plain", "text", "hello"));
    }

    @Test
    void prompts_areListedAndArgumentsInterpolated() {
        McpRouter router = new McpRouter().prompt("greet", "Greeting", "Hello {name}", "name");

        assertThat(router.listPrompts().get("prompts")).asList().first()
                .isEqualTo(Map.of("name", "greet", "description", "Greeting",
                        "arguments", java.util.List.of(Map.of("name", "name", "required", true))));
        assertThat(router.getPrompt("greet", Map.of("name", "Ada")).toString()).contains("Hello Ada");
    }
}
