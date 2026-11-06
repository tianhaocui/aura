package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;
import io.aura.contract.RuntimeState;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleConcurrencyTest {
    @Test
    void stopWaitsForStartingLifecycleAndLeavesRuntimeStopped() throws Exception {
        CountDownLatch moduleStarting = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        AuraModule module = new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("blocking-start", "1.0.0");
            }

            @Override public void install(ModuleContext context) { }

            @Override public void start(ModuleContext context) throws Exception {
                moduleStarting.countDown();
                releaseStart.await();
            }
        };
        AuraApp app = AuraApp.create(new AuraConfig().port(0)).use(module);
        CompletableFuture<Void> starting = CompletableFuture.runAsync(app::start);
        assertThat(moduleStarting.await(2, TimeUnit.SECONDS)).isTrue();

        CountDownLatch stopInvoked = new CountDownLatch(1);
        CompletableFuture<Void> stopping = CompletableFuture.runAsync(() -> {
            stopInvoked.countDown();
            app.stop();
        });
        try {
            assertThat(stopInvoked.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            assertThat(stopping.isDone()).isFalse();
        } finally {
            releaseStart.countDown();
        }
        starting.get(5, TimeUnit.SECONDS);
        stopping.get(5, TimeUnit.SECONDS);

        assertThat(app.state()).isEqualTo(RuntimeState.STOPPED);
        assertThat(app.isRunning()).isFalse();
    }
}
