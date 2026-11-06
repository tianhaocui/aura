package io.aura.contract;

/** Stable application lifecycle state shared by runtime implementations. */
public enum RuntimeState {
    NEW,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED
}
