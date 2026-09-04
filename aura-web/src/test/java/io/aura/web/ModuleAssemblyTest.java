package io.aura.web;

import io.aura.Aura;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleAssemblyTest {
    @Test
    void moduleRoutesAreAvailableToInMemoryTestClient() {
        Aura app = Aura.create().use(new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("greeting", "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                context.routes(routes -> routes.get("/greeting", ctx -> ctx.text("hello")));
            }
        });

        assertThat(TestClient.of(app).get("/greeting").execute().status()).isEqualTo(200);
        assertThat(TestClient.of(app).get("/greeting").execute().body()).isEqualTo("hello");
    }
}
