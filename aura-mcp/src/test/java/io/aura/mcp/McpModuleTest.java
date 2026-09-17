package io.aura.mcp;

import io.aura.AuraApp;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpModuleTest {
    @Test
    void contributesRouterThroughAuraModuleAssembly() {
        McpRouter router = new McpRouter();
        AuraApp app = AuraApp.create().use(McpModule.create(router));

        app.prepare();

        assertThat(app.require(McpRouter.class)).isSameAs(router);
        assertThat(router.buildSchema()).containsKey("tools");
    }
}
