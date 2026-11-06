package io.aura;

import io.aura.contract.ModuleMetadata;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CronContributionTest {

    @Test
    void rejectsAnUnparseableExpressionWithTheFieldThatBrokeIt() {
        assertThatThrownBy(() -> AuraApp.create()
                .use(module("reminder", context -> context.cron("0 0 31 2 *", () -> { })))
                .prepare())
                .isInstanceOf(ModuleException.class)
                .extracting("code", "module").containsExactly(
                        "AURA_CRON_EXPRESSION_INVALID", "reminder");

        assertThatThrownBy(() -> AuraApp.create()
                .use(module("reminder", context -> context.cron("0 25 * * *", () -> { })))
                .prepare())
                .isInstanceOf(ModuleException.class)
                .hasMessageContaining("hour field \"25\"");
    }

    @Test
    void rejectsAContributionWithoutAnExpressionOrATask() {
        assertThatThrownBy(() -> AuraApp.create()
                .use(module("reminder", context -> context.cron("  ", () -> { }))).prepare())
                .isInstanceOf(ModuleException.class)
                .extracting("code").isEqualTo("AURA_CRON_CONTRIBUTION_INVALID");

        assertThatThrownBy(() -> AuraApp.create()
                .use(module("reminder", context -> context.cron("0 9 * * *", null))).prepare())
                .isInstanceOf(ModuleException.class)
                .extracting("code").isEqualTo("AURA_CRON_CONTRIBUTION_INVALID");
    }

    @Test
    void acceptsEveryStandardFiveFieldFormDuringInstall() {
        AuraApp.create()
                .use(module("reminder", context -> {
                    context.cron("0 9 * * *", () -> { });
                    context.cron("*/5 * * * *", () -> { });
                    context.cron("0,30 8-10 1-15 1,6 1-5", () -> { });
                    context.cron("0 0 29 2 *", () -> { });
                    context.cron("0 0 * * 7", () -> { });
                }))
                .prepare();
    }

    private static AuraModule module(String id, java.util.function.Consumer<ModuleContext> contribute) {
        return new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of(id, "1.0.0");
            }

            @Override public void install(ModuleContext context) { contribute.accept(context); }
        };
    }
}
