package io.aura;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static io.aura.Validate.ValidationException;

class ValidateTest {

    // --- notNull ---

    @Test
    void notNull_throwsOnNull() {
        assertThatThrownBy(() -> Validate.notNull(null, "must not be null"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("must not be null");
    }

    @Test
    void notNull_passesOnNonNull() {
        assertThatNoException().isThrownBy(() -> Validate.notNull("value", "msg"));
    }

    // --- notBlank ---

    @Test
    void notBlank_throwsOnNull() {
        assertThatThrownBy(() -> Validate.notBlank(null, "blank"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void notBlank_throwsOnEmpty() {
        assertThatThrownBy(() -> Validate.notBlank("", "blank"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void notBlank_throwsOnWhitespace() {
        assertThatThrownBy(() -> Validate.notBlank("   ", "blank"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void notBlank_passesOnValidString() {
        assertThatNoException().isThrownBy(() -> Validate.notBlank("hello", "msg"));
    }

    // --- range(int) ---

    @Test
    void rangeInt_throwsBelowMin() {
        assertThatThrownBy(() -> Validate.range(1, 5, 10, "out of range"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rangeInt_throwsAboveMax() {
        assertThatThrownBy(() -> Validate.range(11, 5, 10, "out of range"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rangeInt_passesAtMin() {
        assertThatNoException().isThrownBy(() -> Validate.range(5, 5, 10, "msg"));
    }

    @Test
    void rangeInt_passesAtMax() {
        assertThatNoException().isThrownBy(() -> Validate.range(10, 5, 10, "msg"));
    }

    @Test
    void rangeInt_passesWithin() {
        assertThatNoException().isThrownBy(() -> Validate.range(7, 5, 10, "msg"));
    }

    // --- range(long) ---

    @Test
    void rangeLong_throwsBelowMin() {
        assertThatThrownBy(() -> Validate.range(1L, 5L, 10L, "out of range"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rangeLong_throwsAboveMax() {
        assertThatThrownBy(() -> Validate.range(11L, 5L, 10L, "out of range"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rangeLong_passesWithin() {
        assertThatNoException().isThrownBy(() -> Validate.range(7L, 5L, 10L, "msg"));
    }

    // --- minLength ---

    @Test
    void minLength_throwsOnNull() {
        assertThatThrownBy(() -> Validate.minLength(null, 3, "too short"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void minLength_throwsOnShortString() {
        assertThatThrownBy(() -> Validate.minLength("ab", 3, "too short"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void minLength_passesAtExactMin() {
        assertThatNoException().isThrownBy(() -> Validate.minLength("abc", 3, "msg"));
    }

    @Test
    void minLength_passesOnLongerString() {
        assertThatNoException().isThrownBy(() -> Validate.minLength("abcdef", 3, "msg"));
    }

    // --- maxLength ---

    @Test
    void maxLength_passesOnNull() {
        assertThatNoException().isThrownBy(() -> Validate.maxLength(null, 5, "msg"));
    }

    @Test
    void maxLength_throwsOnTooLong() {
        assertThatThrownBy(() -> Validate.maxLength("abcdef", 5, "too long"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void maxLength_passesAtExactMax() {
        assertThatNoException().isThrownBy(() -> Validate.maxLength("abcde", 5, "msg"));
    }

    @Test
    void maxLength_passesOnShorterString() {
        assertThatNoException().isThrownBy(() -> Validate.maxLength("ab", 5, "msg"));
    }

    // --- matches ---

    @Test
    void matches_throwsOnNull() {
        assertThatThrownBy(() -> Validate.matches(null, "\\d+", "no match"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void matches_throwsOnMismatch() {
        assertThatThrownBy(() -> Validate.matches("abc", "\\d+", "no match"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void matches_passesOnMatch() {
        assertThatNoException().isThrownBy(() -> Validate.matches("123", "\\d+", "msg"));
    }

    // --- isTrue ---

    @Test
    void isTrue_throwsOnFalse() {
        assertThatThrownBy(() -> Validate.isTrue(false, "must be true"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("must be true");
    }

    @Test
    void isTrue_passesOnTrue() {
        assertThatNoException().isThrownBy(() -> Validate.isTrue(true, "msg"));
    }
}
