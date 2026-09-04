package io.aura.mcp;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpModuleTest {
    @Test
    void contributesRouterThroughAuraModuleAssembly() {
        McpRouter router = new McpRouter();
        Aura app = Aura.create().use(McpModule.create(router));

        app.prepareModules();

        assertThat(app.getBean(McpRouter.class)).isSameAs(router);
        assertThat(app.mcpRouter()).isSameAs(router);
        assertThat(router.buildSchema()).containsKey("tools");
    }
}
