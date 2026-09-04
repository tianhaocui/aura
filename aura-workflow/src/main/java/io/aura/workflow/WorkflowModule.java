package io.aura.workflow;

import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;
import io.aura.contract.RouteContribution;
import io.aura.web.BaseHandler;

/** Aura module that exposes one shared workflow registry and runtime. */
public final class WorkflowModule implements AuraModule {
    private final WorkflowRegistry registry;
    private final WorkflowExecutionStore store;

    private WorkflowModule(WorkflowRegistry registry, WorkflowExecutionStore store) {
        this.registry = registry;
        this.store = store;
    }

    public static WorkflowModule create() {
        return new WorkflowModule(new WorkflowRegistry(), new InMemoryWorkflowExecutionStore());
    }

    public static WorkflowModule create(WorkflowRegistry registry) {
        return new WorkflowModule(java.util.Objects.requireNonNull(registry, "registry"), new InMemoryWorkflowExecutionStore());
    }

    public static WorkflowModule create(WorkflowRegistry registry, WorkflowExecutionStore store) {
        return new WorkflowModule(java.util.Objects.requireNonNull(registry, "registry"),
                java.util.Objects.requireNonNull(store, "store"));
    }

    @Override public ModuleMetadata descriptor() {
        return ModuleMetadata.of("workflow", "1.0.0")
                .provides("workflow.registry", "workflow.runtime");
    }

    @Override public void install(ModuleContext context) {
        context.register(registry);
        context.register(store);
        context.register(new WorkflowRuntime<>(registry, store));
        context.route(new RouteContribution("GET", "/__schema__/workflows",
                (BaseHandler) request -> request.json(registry.schema())));
    }

    public WorkflowRegistry registry() { return registry; }
}
