package io.aura.workflow;

/**
 * What cancellation does to the side effects of already succeeded steps.
 * {@link #PRESERVE_COMPLETED} (the default) stops further steps and keeps those
 * side effects; {@link #COMPENSATE_ON_CANCEL} additionally runs the registered
 * compensations of completed compensatable steps in reverse order before the
 * execution is marked {@code CANCELLED}.
 */
public enum CancellationPolicy {
    PRESERVE_COMPLETED,
    COMPENSATE_ON_CANCEL
}