package io.aura.workflow;

import java.util.Objects;

public final class WorkflowRuntime<C> {
    private final WorkflowRegistry registry;

    public WorkflowRuntime() { this(new WorkflowRegistry()); }
    public WorkflowRuntime(WorkflowRegistry registry) { this.registry = Objects.requireNonNull(registry, "registry"); }
    public WorkflowRegistry registry() { return registry; }

    public WorkflowResult execute(String workflowId, C context, WorkflowSelector<C> selector) {
        @SuppressWarnings("unchecked") WorkflowDefinition<C> definition = (WorkflowDefinition<C>) registry.get(workflowId);
        return definition.execute(context, selector);
    }
    public WorkflowResult execute(String workflowId, C context) {
        return execute(workflowId, context, WorkflowSelector.defaultVariant());
    }
    public WorkflowResult execute(WorkflowDefinition<C> definition, C context, WorkflowSelector<C> selector) {
        return definition.execute(context, selector);
    }
}
