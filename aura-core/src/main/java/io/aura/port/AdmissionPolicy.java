package io.aura.port;

/** Replaceable request admission policy for rate limits and concurrency bulkheads. */
@FunctionalInterface
public interface AdmissionPolicy {
    AdmissionDecision admit(AdmissionRequest request);
}
