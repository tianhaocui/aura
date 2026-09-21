package ${package};

import io.aura.AuraApp;
import io.aura.AuraModule;
import io.aura.HealthModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;
import io.aura.contract.RouteContribution;
import io.aura.web.BaseHandler;

public final class App {
    private App() { }

    public static AuraApp create() {
        return AuraApp.create()
                .use(HealthModule.create())
                .use(new AppModule());
    }

    public static void main(String[] args) {
        create().start(args);
    }

    private static final class AppModule implements AuraModule {
        @Override public ModuleMetadata descriptor() {
            return ModuleMetadata.of("app", "1.0.0").provides("app.api");
        }

        @Override public void install(ModuleContext context) {
            context.route(new RouteContribution(
                    "GET", "/hello", (BaseHandler) request -> request.text("hello")));
        }
    }
}
