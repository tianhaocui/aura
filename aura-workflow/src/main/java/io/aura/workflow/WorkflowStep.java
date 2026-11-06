package io.aura.workflow;

@FunctionalInterface
public interface WorkflowStep<C> {
    Object execute(C context) throws Exception;
}
