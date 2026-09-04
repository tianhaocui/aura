package example;

import io.aura.AuraApp;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;
import io.aura.contract.RouteContribution;
import io.aura.web.BaseHandler;
import io.aura.workflow.Workflow;
import io.aura.workflow.WorkflowModule;
import io.aura.workflow.WorkflowRegistry;

import java.util.List;

/** Minimal business module composition example used by the CI build. */
public final class ComposedApp {
    private ComposedApp() { }

    public static AuraApp create() {
        return AuraApp.create()
                .use(WorkflowModule.create())
                .use(new OrderModule());
    }

    public static final class OrderModule implements AuraModule {
        @Override public ModuleMetadata descriptor() {
            return ModuleMetadata.of("orders", "1.0.0")
                    .requires("workflow")
                    .provides("orders.api", "orders.workflow");
        }

        @Override public void install(ModuleContext context) {
            WorkflowRegistry workflows = context.require(WorkflowRegistry.class);
            context.route(new RouteContribution("GET", "/orders",
                    (BaseHandler) request -> request.json(List.of("ready"))));
            workflows.register(Workflow.<List<String>>builder("orders.fulfillment")
                    .step("place-order", events -> { events.add("place-order"); return null; })
                    .step("outbound", events -> { events.add("outbound"); return null; })
                    .before("place-order", "outbound")
                    .build());
        }
    }
}
