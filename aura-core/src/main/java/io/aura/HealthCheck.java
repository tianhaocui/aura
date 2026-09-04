package io.aura;

@FunctionalInterface
public interface HealthCheck {
    HealthStatus check() throws Exception;
}
