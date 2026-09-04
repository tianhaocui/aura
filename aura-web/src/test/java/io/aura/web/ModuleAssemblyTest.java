package io.aura.web;

import io.aura.Aura;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.ModuleDescriptor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleAssemblyTest {
    @Test
    void moduleRoutesAreAvailableToInMemoryTestClient() {
        Aura app = Aura.create().use(new AuraModule() {
            @Override public ModuleDescriptor descriptor() {
                return ModuleDescriptor.of("greeting", "1.0.0").build();
            }

            @Override public void install(ModuleContext context) {
                context.routes(routes -> routes.get("/greeting", ctx -> ctx.text("hello")));
            }
        });

        assertThat(TestClient.of(app).get("/greeting").execute().status()).isEqualTo(200);
        assertThat(TestClient.of(app).get("/greeting").execute().body()).isEqualTo("hello");
    }
}
