package example.generated;

import io.aura.AuraApp;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;

public final class GeneratedApp {
    private GeneratedApp() { }

    public static AuraApp create() {
        GeneratedOrderService service = new GeneratedOrderService();
        return AuraApp.create().use(new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("generated-orders", "1.0.0")
                        .provides("generated-orders.api");
            }

            @Override public void install(ModuleContext context) {
                new GeneratedOrderServiceAuraRoutes(service).routes().forEach(context::route);
            }
        });
    }
}
