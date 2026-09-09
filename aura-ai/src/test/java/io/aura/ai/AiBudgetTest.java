package io.aura.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AiBudgetTest {
    @Test
    void builderPopulatesAllLimitsAndUnlimitedLeavesUsageUnbounded() {
        AiBudget budget = AiBudget.builder()
                .maxInputCharacters(10)
                .maxOutputCharacters(20)
                .maxInputTokens(30)
                .maxOutputTokens(40)
                .maxTotalTokens(50)
                .maxCostMicros(60)
                .build();

        assertThat(budget.maxInputCharacters()).isEqualTo(10);
        assertThat(budget.maxOutputCharacters()).isEqualTo(20);
        assertThat(budget.maxInputTokens()).isEqualTo(30);
        assertThat(budget.maxOutputTokens()).isEqualTo(40);
        assertThat(budget.maxTotalTokens()).isEqualTo(50);
        assertThat(budget.maxCostMicros()).isEqualTo(60);
        assertThat(budget.requiresUsage()).isTrue();

        assertThat(AiBudget.unlimited()).isEqualTo(new AiBudget(0, 0, 0, 0, 0, 0));
        assertThat(AiBudget.unlimited().requiresUsage()).isFalse();
    }

    @Test
    void rejectsNegativeLimitsIncludingBuilderValues() {
        assertThatThrownBy(() -> new AiBudget(-1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
        assertThatThrownBy(() -> AiBudget.builder().maxTotalTokens(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
    }
}
