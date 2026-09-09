package io.aura.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiUsageTest {
    @Test
    void parsesCanonicalAndProviderAliasFieldsAndDerivesTotal() {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", "3");
        usage.put("completion_tokens", 4);
        usage.put("cost_micros", 12L);

        AiUsage parsed = AiUsage.from(usage);

        assertThat(parsed).isEqualTo(new AiUsage(3, 4, 7, 12));
        assertThat(parsed.hasTokenUsage()).isTrue();
        assertThat(parsed.hasCost()).isTrue();
    }

    @Test
    void missingOrInvalidUsageIsHandledExplicitly() {
        assertThat(AiUsage.from(null)).isEqualTo(AiUsage.unknown());
        assertThat(AiUsage.from(Map.of())).isEqualTo(AiUsage.unknown());
        assertThat(AiUsage.unknown().hasTokenUsage()).isFalse();
        assertThat(AiUsage.unknown().hasCost()).isFalse();

        assertThatThrownBy(() -> AiUsage.from(Map.of("output_tokens", "not-a-number")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be an integer");
        assertThatThrownBy(() -> AiUsage.from(Map.of("input_tokens", -1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
    }
}
