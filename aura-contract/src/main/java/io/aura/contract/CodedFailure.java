package io.aura.contract;

/** Failure with a stable machine-readable code suitable for wrapping across module boundaries. */
public interface CodedFailure {
    String code();
}
