package io.aura.workflow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public final class WorkflowDefinition<C> implements AuraWorkflow<C> {
    private final String id;
    private final Map<String, WorkflowStepSpec<C>> baseSteps;
    private final Set<WorkflowVariantBuilder.Edge> baseEdges;
    private final Map<String, Variant<C>> variants;

    private WorkflowDefinition(String id, Map<String, WorkflowStepSpec<C>> steps,
                               Set<WorkflowVariantBuilder.Edge> edges,
                               Map<String, Variant<C>> variants) {
        this.id = id;
        this.baseSteps = Collections.unmodifiableMap(new LinkedHashMap<>(steps));
        this.baseEdges = Collections.unmodifiableSet(new LinkedHashSet<>(edges));
        this.variants = Collections.unmodifiableMap(new LinkedHashMap<>(variants));
        validate(this.baseSteps, this.baseEdges);
        for (Variant<C> variant : variants.values()) validate(variant.steps, variant.edges);
    }

    public String id() { return id; }
    public Set<String> variants() { return variants.keySet(); }
    public List<String> order(String variant) {
        Resolved<C> resolved = resolve(variant);
        return topologicalOrder(resolved.steps, resolved.edges);
    }

    @Override
    public WorkflowResult execute(C context, WorkflowSelector<C> selector) {
        Objects.requireNonNull(selector, "selector");
        String variant = selector.select(context);
        Resolved<C> resolved = resolve(variant);
        List<String> order = topologicalOrder(resolved.steps, resolved.edges);
        WorkflowExecution execution = new WorkflowExecution(id, variant);
        for (String stepId : order) {
            WorkflowStepSpec<C> spec = resolved.steps.get(stepId);
            execution.add(new StepExecution(stepId, spec.mode(), spec.retryPolicy()));
        }
        execution.start();
        Object value = null;
        List<WorkflowStepSpec<C>> completed = new ArrayList<>();
        try {
            for (String stepId : order) {
                WorkflowStepSpec<C> spec = resolved.steps.get(stepId);
                StepExecution state = execution.step(stepId);
                state.start();
                Throwable failure = null;
                for (int attempt = 1; attempt <= spec.retryPolicy().maxAttempts(); attempt++) {
                    state.attempt();
                    try {
                        value = spec.step().execute(context);
                        state.success(value);
                        failure = null;
                        break;
                    } catch (Exception error) {
                        failure = error;
                        if (attempt >= spec.retryPolicy().maxAttempts() || !spec.retryPolicy().shouldRetry(error)) break;
                        if (!spec.retryPolicy().delay().isZero()) {
                            try { Thread.sleep(spec.retryPolicy().delay().toMillis()); }
                            catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                failure = interrupted;
                                break;
                            }
                        }
                    }
                }
                if (failure != null) {
                    state.fail(failure);
                    compensate(context, completed, execution);
                    execution.fail(failure);
                    return new WorkflowResult(execution, null);
                }
                completed.add(spec);
            }
            execution.success();
            return new WorkflowResult(execution, value);
        } catch (Exception error) {
            execution.fail(error);
            return new WorkflowResult(execution, null);
        }
    }

    private void compensate(C context, List<WorkflowStepSpec<C>> completed, WorkflowExecution execution) {
        for (int i = completed.size() - 1; i >= 0; i--) {
            WorkflowStepSpec<C> spec = completed.get(i);
            if (!spec.mode().compensatable() || spec.compensation() == null) continue;
            try {
                spec.compensation().compensate(context, execution.step(spec.id()).result());
                execution.step(spec.id()).compensated();
            } catch (Exception compensationError) {
                execution.step(spec.id()).fail(compensationError);
            }
        }
    }

    private Resolved<C> resolve(String variant) {
        if (variant == null || variant.isBlank()) return new Resolved<>(baseSteps, baseEdges);
        Variant<C> selected = variants.get(variant);
        if (selected == null) throw new WorkflowException(WorkflowException.UNKNOWN_VARIANT,
                "Unknown workflow variant '" + variant + "' for " + id);
        return new Resolved<>(selected.steps, selected.edges);
    }

    private static <C> void validate(Map<String, WorkflowStepSpec<C>> steps, Set<WorkflowVariantBuilder.Edge> edges) {
        for (WorkflowVariantBuilder.Edge edge : edges) {
            if (!steps.containsKey(edge.from()) || !steps.containsKey(edge.to())) {
                throw new WorkflowException(WorkflowException.MISSING_STEP,
                        "Dependency references missing step: " + edge.from() + " -> " + edge.to());
            }
            if (edges.contains(new WorkflowVariantBuilder.Edge(edge.to(), edge.from())) && !edge.from().equals(edge.to())) {
                throw new WorkflowException(WorkflowException.ORDER_CONFLICT,
                        "Conflicting order constraints: " + edge.from() + " and " + edge.to());
            }
        }
        topologicalOrder(steps, edges);
    }

    private static <C> List<String> topologicalOrder(Map<String, WorkflowStepSpec<C>> steps,
                                                      Set<WorkflowVariantBuilder.Edge> edges) {
        Map<String, Integer> incoming = new LinkedHashMap<>();
        Map<String, Set<String>> outgoing = new LinkedHashMap<>();
        steps.keySet().forEach(id -> { incoming.put(id, 0); outgoing.put(id, new LinkedHashSet<>()); });
        edges.forEach(edge -> {
            if (outgoing.get(edge.from()).add(edge.to())) incoming.put(edge.to(), incoming.get(edge.to()) + 1);
        });
        Deque<String> ready = new ArrayDeque<>();
        incoming.forEach((id, count) -> { if (count == 0) ready.add(id); });
        List<String> result = new ArrayList<>();
        while (!ready.isEmpty()) {
            String current = ready.remove();
            result.add(current);
            for (String next : outgoing.get(current)) if (incoming.put(next, incoming.get(next) - 1) == 1) ready.add(next);
        }
        if (result.size() != steps.size()) {
            throw new WorkflowException(WorkflowException.DEPENDENCY_CYCLE, "Workflow dependency graph contains a cycle");
        }
        return List.copyOf(result);
    }

    public static <C> Builder<C> builder(String id) { return new Builder<>(id); }

    public static final class Builder<C> {
        private final String id;
        private final WorkflowVariantBuilder<C> base = new WorkflowVariantBuilder<>();
        private final Map<String, Variant<C>> variants = new LinkedHashMap<>();
        private Builder(String id) {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("workflow id must not be blank");
            this.id = id;
        }
        public Builder<C> step(String stepId, WorkflowStep<C> step) { base.step(stepId, step); return this; }
        public Builder<C> step(String stepId, WorkflowStep<C> step, StepMode mode) { base.step(stepId, step, mode); return this; }
        public Builder<C> step(String stepId, WorkflowStep<C> step, StepMode mode, RetryPolicy retry) {
            base.step(stepId, step, mode, retry); return this;
        }
        public Builder<C> before(String first, String second) { base.before(first, second); return this; }
        public Builder<C> after(String first, String second) { base.after(first, second); return this; }
        public Builder<C> insertBefore(String anchorId, String stepId, WorkflowStep<C> step) {
            base.insertBefore(anchorId, stepId, step); return this;
        }
        public Builder<C> insertAfter(String anchorId, String stepId, WorkflowStep<C> step) {
            base.insertAfter(anchorId, stepId, step); return this;
        }
        public Builder<C> compensate(String stepId, WorkflowCompensation<C> compensation) {
            base.compensate(stepId, compensation); return this;
        }
        public Builder<C> variant(String name, Consumer<WorkflowVariantBuilder<C>> extension) {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("variant name must not be blank");
            if (variants.containsKey(name)) throw new WorkflowException(WorkflowException.DUPLICATE_STEP, "Duplicate variant: " + name);
            WorkflowVariantBuilder<C> builder = copyBase();
            extension.accept(builder);
            variants.put(name, new Variant<>(new LinkedHashMap<>(builder.steps()), new LinkedHashSet<>(builder.edges())));
            return this;
        }
        public Builder<C> extension(String name, WorkflowExtension<C> extension) {
            return variant(name, extension::apply);
        }
        public WorkflowDefinition<C> build() { return new WorkflowDefinition<>(id, base.steps(), base.edges(), variants); }
        private WorkflowVariantBuilder<C> copyBase() {
            WorkflowVariantBuilder<C> copy = new WorkflowVariantBuilder<>();
            base.steps().forEach((stepId, spec) -> copy.step(stepId, spec.step(), spec.mode(), spec.retryPolicy()));
            base.steps().forEach((stepId, spec) -> { if (spec.compensation() != null) copy.compensate(stepId, spec.compensation()); });
            base.edges().forEach(edge -> copy.edges().add(edge));
            return copy;
        }
    }

    private record Variant<C>(Map<String, WorkflowStepSpec<C>> steps, Set<WorkflowVariantBuilder.Edge> edges) {}
    private record Resolved<C>(Map<String, WorkflowStepSpec<C>> steps, Set<WorkflowVariantBuilder.Edge> edges) {}
}
