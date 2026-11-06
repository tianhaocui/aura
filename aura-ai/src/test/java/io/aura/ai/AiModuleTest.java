package io.aura.ai;

import io.aura.AuraApp;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiModuleTest {
    @Test
    void contributesClientThroughAuraModuleAssembly() {
        AiProvider provider = request -> AiResponse.text("ok");
        AiModule module = AiModule.create(provider);
        AuraApp app = AuraApp.create().use(module);

        app.prepare();

        assertThat(app.require(AiClient.class)).isSameAs(module.client());
        assertThat(module.client().provider()).isSameAs(provider);
        module.client().close();
    }
}
