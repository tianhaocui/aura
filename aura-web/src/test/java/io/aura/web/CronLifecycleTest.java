package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.ModuleException;
import io.aura.contract.ModuleMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CronLifecycleTest {

    @Test
    void startingArmsTheCronPoolAndStoppingEndsItsThread() throws Exception {
        AtomicReference<ModuleContext> installed = new AtomicReference<>();
        AtomicInteger runs = new AtomicInteger();
        AuraApp app = AuraApp.create(new AuraConfig().port(0)).use(new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("reminder", "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                installed.set(context);
                context.cron("0 9 * * *", runs::incrementAndGet);
            }
        });

        try {
            app.start();
            assertThat(cronThreads()).isNotEmpty();
            assertThatThrownBy(() -> installed.get().cron("*/5 * * * *", () -> { }))
                    .isInstanceOf(ModuleException.class)
                    .extracting("code").isEqualTo("AURA_CRON_CONTRIBUTION_TOO_LATE");
        } finally {
            app.close();
        }

        assertThat(runs).hasValue(0);
        awaitNoCronThreads();
    }

    @Test
    void aRuntimeWithoutCronTasksNeverOpensAPool() throws Exception {
        AuraApp app = AuraApp.create(new AuraConfig().port(0)).use(new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("plain", "1.0.0");
            }

            @Override public void install(ModuleContext context) { }
        });

        try {
            app.start();
            assertThat(cronThreads()).isEmpty();
        } finally {
            app.close();
        }
    }

    private static List<Thread> cronThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith("aura-cron-"))
                .toList();
    }

    private static void awaitNoCronThreads() throws InterruptedException {
        for (int attempt = 0; attempt < 200 && !cronThreads().isEmpty(); attempt++) {
            Thread.sleep(25);
        }
        assertThat(cronThreads()).isEmpty();
    }
}
