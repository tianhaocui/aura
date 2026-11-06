package io.aura.workflow;

@FunctionalInterface
public interface WorkflowExtension<C> {
    void apply(WorkflowVariantBuilder<C> variant);
}
