package io.aura.workflow;

public interface Workflow<C> {
    WorkflowResult execute(C context, WorkflowSelector<C> selector);

    default WorkflowResult execute(C context) {
        return execute(context, WorkflowSelector.defaultVariant());
    }

    static <C> WorkflowDefinition.Builder<C> builder(String id) {
        return WorkflowDefinition.builder(id);
    }
}
