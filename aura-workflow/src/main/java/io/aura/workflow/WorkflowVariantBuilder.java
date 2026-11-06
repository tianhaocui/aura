package io.aura.workflow;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class WorkflowVariantBuilder<C> {
    private final Map<String, WorkflowStepSpec<C>> steps = new LinkedHashMap<>();
    private final Set<Edge> edges = new LinkedHashSet<>();

    WorkflowVariantBuilder() {}

    public WorkflowVariantBuilder<C> step(String id, WorkflowStep<C> step) {
        return step(id, step, StepMode.NORMAL, RetryPolicy.none());
    }
    public WorkflowVariantBuilder<C> step(String id, WorkflowStep<C> step, StepMode mode) {
        return step(id, step, mode, RetryPolicy.none());
    }
    public WorkflowVariantBuilder<C> step(String id, WorkflowStep<C> step, StepMode mode, RetryPolicy retry) {
        requireId(id);
        if (steps.putIfAbsent(id, new WorkflowStepSpec<>(id, step, mode, retry, null)) != null) {
            throw new WorkflowException(WorkflowException.DUPLICATE_STEP, "Duplicate variant step: " + id);
        }
        return this;
    }
    public WorkflowVariantBuilder<C> compensate(String id, WorkflowCompensation<C> compensation) {
        WorkflowStepSpec<C> existing = steps.get(id);
        if (existing == null) throw new WorkflowException(WorkflowException.MISSING_STEP, "Unknown variant step: " + id);
        steps.put(id, new WorkflowStepSpec<>(id, existing.step(), existing.mode(), existing.retryPolicy(), compensation));
        return this;
    }
    public WorkflowVariantBuilder<C> insertBefore(String anchorId, String id, WorkflowStep<C> step) {
        return insertBefore(anchorId, id, step, StepMode.NORMAL, RetryPolicy.none());
    }
    public WorkflowVariantBuilder<C> insertBefore(String anchorId, String id, WorkflowStep<C> step,
                                                  StepMode mode, RetryPolicy retry) {
        step(id, step, mode, retry); edges.add(new Edge(id, anchorId)); return this;
    }
    public WorkflowVariantBuilder<C> insertAfter(String anchorId, String id, WorkflowStep<C> step) {
        return insertAfter(anchorId, id, step, StepMode.NORMAL, RetryPolicy.none());
    }
    public WorkflowVariantBuilder<C> insertAfter(String anchorId, String id, WorkflowStep<C> step,
                                                 StepMode mode, RetryPolicy retry) {
        step(id, step, mode, retry); edges.add(new Edge(anchorId, id)); return this;
    }
    public WorkflowVariantBuilder<C> before(String first, String second) { edges.add(new Edge(first, second)); return this; }
    public WorkflowVariantBuilder<C> after(String first, String second) { edges.add(new Edge(second, first)); return this; }

    Map<String, WorkflowStepSpec<C>> steps() { return steps; }
    Set<Edge> edges() { return edges; }
    private static void requireId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("step id must not be blank");
    }
    record Edge(String from, String to) {
        Edge { Objects.requireNonNull(from); Objects.requireNonNull(to); }
    }
}
