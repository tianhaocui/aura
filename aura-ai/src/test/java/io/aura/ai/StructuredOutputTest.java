package io.aura.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StructuredOutputTest {
    record Answer(String answer, int score) { }

    @Test
    void parsesAndValidatesJsonResponse() {
        Answer answer = StructuredOutput.parseAndValidate(
                AiResponse.text("{\"answer\":\"ok\",\"score\":7}"), Answer.class,
                value -> { if (value.score() < 1) throw new IllegalArgumentException("score"); });
        assertThat(answer.answer()).isEqualTo("ok");
        assertThat(answer.score()).isEqualTo(7);
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> StructuredOutput.parse("{bad", Answer.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid structured output JSON");
        assertThatThrownBy(() -> StructuredOutput.validateJson("not-json"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
