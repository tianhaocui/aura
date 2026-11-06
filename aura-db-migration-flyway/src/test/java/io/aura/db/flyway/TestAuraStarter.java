package io.aura.db.flyway;

import io.aura.AuraStarter;
import io.aura.AuraWebRuntime;

/** Keeps lifecycle tests in-process without binding a network listener. */
public final class TestAuraStarter implements AuraStarter {
    @Override public void start(AuraWebRuntime app) { }
    @Override public void stop() { }
}
