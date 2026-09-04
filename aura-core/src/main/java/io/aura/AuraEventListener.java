package io.aura;

@FunctionalInterface
public interface AuraEventListener {
    void onEvent(AuraEvent event);
}
