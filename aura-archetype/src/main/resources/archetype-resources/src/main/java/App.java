package ${package};

import io.aura.AuraApp;
import io.aura.AuraModule;
import io.aura.web.HealthModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;
import io.aura.contract.Route;
import io.aura.port.BaseHandler;

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
            context.route(Route.of("GET", "/hello",
                    (BaseHandler) request -> request.text("hello")));
        }
    }
}
