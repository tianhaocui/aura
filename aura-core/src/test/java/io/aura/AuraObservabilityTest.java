package io.aura;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuraObservabilityTest {
    @Test
    void emitsImmutableEventsAndKeepsListenerFailuresIsolated() {
        List<AuraEvent> received = new ArrayList<>();
        AuraApp app = AuraApp.create()
                .observe(event -> { throw new IllegalStateException("listener failure"); })
                .observe(received::add);

        AuraEvent event = AuraEvent.of("request.completed", java.util.Map.of("status", 200));
        ((AuraWebRuntime) app.runtimeView()).emit(event);

        assertThat(received).containsExactly(event);
        assertThat(event.attributes()).containsEntry("status", 200);
    }

    @Test
    void shutdownFailuresAreObservableWithoutBreakingTerminalStateOrLeakingMessages() {
        List<AuraEvent> received = new ArrayList<>();
        AuraModule module = new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                return io.aura.contract.ModuleMetadata.of("failing-cleanup", "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                context.register((AutoCloseable) () -> {
                    throw new IllegalStateException("resource password=secret");
                });
            }

            @Override public void stop(ModuleContext context) {
                throw new IllegalStateException("module token=secret");
            }
        };
        AuraApp app = AuraApp.create()
                .observe(event -> { throw new AssertionError("observer failure"); })
                .observe(received::add)
                .use(module)
                .prepare();

        app.stop();

        assertThat(app.state()).isEqualTo(io.aura.contract.RuntimeState.STOPPED);
        assertThat(received).extracting(AuraEvent::type)
                .containsExactly(
                        "runtime.shutdown.failure",
                        "runtime.shutdown.failure",
                        "runtime.shutdown.completed");
        assertThat(received.get(0).attributes().get("component").toString())
                .startsWith("module:");
        assertThat(received.get(1).attributes().get("component").toString())
                .isEqualTo("resource");
        assertThat(received.get(2).attributes()).containsEntry("failureCount", 2);
        assertThat(received.toString()).doesNotContain("password=secret", "token=secret");
    }

    @Test
    void startupRollbackRetainsCleanupCauseAndEmitsStableEvents() {
        List<AuraEvent> received = new ArrayList<>();
        AuraModule module = new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                return io.aura.contract.ModuleMetadata.of("startup-cleanup", "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                context.register((AutoCloseable) () -> {
                    throw new IllegalStateException("cleanup failed");
                });
            }

            @Override public void start(ModuleContext context) {
                throw new IllegalArgumentException("startup failed");
            }

            @Override public void stop(ModuleContext context) {
                throw new IllegalStateException("module cleanup failed");
            }
        };
        AuraApp app = AuraApp.create().observe(received::add).use(module);

        assertThatThrownBy(app::start)
                .isInstanceOfSatisfying(ModuleException.class, error ->
                        assertThat(error.getSuppressed())
                                .extracting(Throwable::getMessage)
                                .containsExactly("module cleanup failed", "cleanup failed"));

        assertThat(app.state()).isEqualTo(io.aura.contract.RuntimeState.STOPPED);
        assertThat(received).extracting(AuraEvent::type)
                .containsExactly(
                        "runtime.shutdown.failure",
                        "runtime.shutdown.failure",
                        "runtime.shutdown.completed");
        assertThat(received.get(0).attributes())
                .containsEntry("phase", "startup-rollback")
                .containsEntry("code", "AURA_RUNTIME_SHUTDOWN_FAILED")
                .containsEntry("component", "module:startup-cleanup");
        assertThat(received.get(2).attributes()).containsEntry("failureCount", 2);
    }

    @Test
    void moduleMetadataIsFrozenBeforeCleanupReporting() {
        AtomicInteger descriptorCalls = new AtomicInteger();
        List<AuraEvent> received = new ArrayList<>();
        AuraModule module = new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                if (descriptorCalls.incrementAndGet() > 1) {
                    throw new IllegalStateException("descriptor called again");
                }
                return io.aura.contract.ModuleMetadata.of("stable-metadata", "1.0.0");
            }

            @Override public void install(ModuleContext context) { }

            @Override public void stop(ModuleContext context) {
                throw new IllegalStateException("cleanup failed");
            }
        };
        AuraApp app = AuraApp.create().observe(received::add).use(module).prepare();

        app.stop();

        assertThat(descriptorCalls).hasValue(1);
        assertThat(app.state()).isEqualTo(io.aura.contract.RuntimeState.STOPPED);
        assertThat(received.get(0).attributes())
                .containsEntry("component", "module:stable-metadata");
    }

    @Test
    void sameThrowableFromStartAndStopDoesNotSelfSuppress() {
        RuntimeException shared = new RuntimeException("same failure");
        AuraModule module = new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                return io.aura.contract.ModuleMetadata.of("same-failure", "1.0.0");
            }

            @Override public void install(ModuleContext context) { }
            @Override public void start(ModuleContext context) { throw shared; }
            @Override public void stop(ModuleContext context) { throw shared; }
        };
        AuraApp app = AuraApp.create().use(module);

        assertThatThrownBy(app::start)
                .isInstanceOfSatisfying(ModuleException.class, error -> {
                    assertThat(error.getCause()).isSameAs(shared);
                    assertThat(error.getSuppressed()).isEmpty();
                });
        assertThat(app.state()).isEqualTo(io.aura.contract.RuntimeState.STOPPED);
    }

    @Test
    void fatalListenerFailureIsNotHidden() {
        OutOfMemoryError fatal = new OutOfMemoryError("fatal");
        AuraApp app = AuraApp.create().observe(event -> { throw fatal; });

        assertThatThrownBy(() -> ((AuraWebRuntime) app.runtimeView()).emit(
                AuraEvent.of("test", java.util.Map.of())))
                .isSameAs(fatal);
    }
}
