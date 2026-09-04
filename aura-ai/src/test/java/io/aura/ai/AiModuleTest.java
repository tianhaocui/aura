package io.aura.ai;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiModuleTest {
    @Test
    void contributesClientThroughAuraModuleAssembly() {
        AiProvider provider = request -> AiResponse.text("ok");
        AiModule module = AiModule.create(provider);
        Aura app = Aura.create().use(module);

        app.prepareModules();

        assertThat(app.getBean(AiClient.class)).isSameAs(module.client());
        assertThat(module.client().provider()).isSameAs(provider);
        module.client().close();
    }
}
