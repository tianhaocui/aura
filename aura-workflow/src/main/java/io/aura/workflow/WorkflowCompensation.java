package io.aura.workflow;

@FunctionalInterface
public interface WorkflowCompensation<C> {
    void compensate(C context, Object stepResult) throws Exception;
}
