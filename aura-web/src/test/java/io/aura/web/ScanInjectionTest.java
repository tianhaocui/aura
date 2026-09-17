package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;
import io.aura.web.testfixtures.FakeDb;
import io.aura.web.testfixtures.InjectedController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScanInjectionTest {

    @Test
    void explicitModuleInjectsRegisteredCapabilityIntoService() {
        AuraApp app = AuraApp.create().use(new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("injected-service", "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                context.register(new FakeDb());
                context.service(new InjectedController(context.require(FakeDb.class)));
            }
        });

        TestClient client = TestClient.of(app);
        TestClient.Response res = client.get("/injected").execute();
        assertEquals(200, res.status());
        assertEquals("data", res.body());
    }
}
