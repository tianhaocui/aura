package io.aura.workflow;

import java.util.Objects;
import java.util.function.Function;

@FunctionalInterface
public interface WorkflowSelector<C> {
    String select(C context);

    static <C> WorkflowSelector<C> defaultVariant() { return context -> null; }
    static <C> WorkflowSelector<C> constant(String variant) { return context -> variant; }
    static <C> WorkflowSelector<C> by(Function<C, String> selector) {
        Objects.requireNonNull(selector, "selector");
        return selector::apply;
    }
}
