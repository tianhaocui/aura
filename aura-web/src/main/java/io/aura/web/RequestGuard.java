package io.aura.web;

/** One pre-route policy check shared by every HTTP entry point. */
@FunctionalInterface
public interface RequestGuard {

    /** Returns {@link GuardOutcome#next()} to continue the chain, or a terminal decision. */
    GuardOutcome evaluate(GuardedRequest request);
}
