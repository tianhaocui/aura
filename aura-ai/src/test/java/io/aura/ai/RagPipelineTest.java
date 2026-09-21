package io.aura.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagPipelineTest {
    @Test
    void buildsDeterministicBoundedContextAndSkipsEmptyPassages() throws Exception {
        RagPipeline pipeline = new RagPipeline((query, limit) ->
                java.util.Arrays.asList("  first ", "", null, "second", "third"), 2, 20);

        RagPipeline.RagContext context = pipeline.retrieve("order");

        assertThat(context.query()).isEqualTo("order");
        assertThat(context.passages()).containsExactly("first", "second");
        assertThat(context.text()).isEqualTo("first\n\nsecond");
    }

    @Test
    void truncatesAtCharacterBudgetAndStopsAfterTruncation() throws Exception {
        RagPipeline pipeline = new RagPipeline((query, limit) -> List.of("12345", "67890"), 5, 7);

        assertThat(pipeline.retrieve("q").text()).isEqualTo("12345");
        assertThat(pipeline.retrieve("q").passages()).containsExactly("12345");
    }

    @Test
    void rejectsInvalidConfigurationAndRetrieverResults() {
        assertThatThrownBy(() -> new RagPipeline((q, l) -> List.of(), 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagPipeline((q, l) -> List.of(), 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagPipeline((q, l) -> null, 1, 10).retrieve("q"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RagPipeline((q, l) -> List.of(), 1, 10).retrieve(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
