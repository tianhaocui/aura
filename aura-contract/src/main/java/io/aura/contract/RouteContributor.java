package io.aura.contract;

import java.util.Collection;

/** Supplies framework-neutral route descriptions to an adapter. */
@FunctionalInterface
public interface RouteContributor {
    Collection<RouteContribution> routes();
}
