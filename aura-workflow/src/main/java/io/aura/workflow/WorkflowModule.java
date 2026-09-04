package io.aura.workflow;

import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.ModuleDescriptor;

/** Aura module that exposes one shared workflow registry and runtime. */
public final class WorkflowModule implements AuraModule {
    private final WorkflowRegistry registry;

    private WorkflowModule(WorkflowRegistry registry) {
        this.registry = registry;
    }

    public static WorkflowModule create() {
        return new WorkflowModule(new WorkflowRegistry());
    }

    public static WorkflowModule create(WorkflowRegistry registry) {
        return new WorkflowModule(java.util.Objects.requireNonNull(registry, "registry"));
    }

    @Override public ModuleDescriptor descriptor() {
        return ModuleDescriptor.of("workflow", "1.0.0")
                .provides("workflow.registry", "workflow.runtime")
                .build();
    }

    @Override public void install(ModuleContext context) {
        context.register(registry);
        context.register(new WorkflowRuntime<>(registry));
    }

    public WorkflowRegistry registry() { return registry; }
}
