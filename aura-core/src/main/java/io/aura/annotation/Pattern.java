package io.aura.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates a string with Aura's linear-time-safe regular-expression subset.
 * The subset supports literals, character classes, anchors, escapes, fixed
 * repetitions, and at most one variable repetition. Groups, alternation,
 * backreferences, and other backtracking-prone constructs are rejected. The
 * expression is limited to 1024 UTF-16 code units and validated values to
 * 4096 UTF-16 code units; exceeding either limit produces a validation error.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Pattern {
    String value();
    String message() default "";
}
