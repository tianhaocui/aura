package io.aura.contract;

/** Framework-neutral health probe. */
@FunctionalInterface
public interface HealthCheck {
    HealthStatus check() throws Exception;
}
