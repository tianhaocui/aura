package io.aura;

import io.aura.annotation.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class BeanValidatorTest {

    record ValidRecord(@NotBlank String name, @Min(0) @Max(150) int age) {}
    record PatternRecord(@Pattern("[0-9]+") String phone) {}
    record SizeRecord(@Size(min = 2, max = 10) String code) {}
    record NullableRecord(@NotNull Object ref) {}

    @Test
    void valid_record_passes() {
        assertThatCode(() -> BeanValidator.validate(new ValidRecord("Alice", 25)))
                .doesNotThrowAnyException();
    }

    @Test
    void notBlank_null_fails() {
        assertThatThrownBy(() -> BeanValidator.validate(new ValidRecord(null, 25)))
                .isInstanceOf(Validate.ValidationException.class)
                .hasMessageContaining("name");
    }

    @Test
    void notBlank_empty_fails() {
        assertThatThrownBy(() -> BeanValidator.validate(new ValidRecord("  ", 25)))
                .isInstanceOf(Validate.ValidationException.class)
                .hasMessageContaining("name");
    }

    @Test
    void min_violation_fails() {
        assertThatThrownBy(() -> BeanValidator.validate(new ValidRecord("Bob", -1)))
                .isInstanceOf(Validate.ValidationException.class)
                .hasMessageContaining("age");
    }

    @Test
    void max_violation_fails() {
        assertThatThrownBy(() -> BeanValidator.validate(new ValidRecord("Bob", 200)))
                .isInstanceOf(Validate.ValidationException.class)
                .hasMessageContaining("age");
    }

    @Test
    void pattern_valid_passes() {
        assertThatCode(() -> BeanValidator.validate(new PatternRecord("12345")))
                .doesNotThrowAnyException();
    }

    @Test
    void pattern_invalid_fails() {
        assertThatThrownBy(() -> BeanValidator.validate(new PatternRecord("abc")))
                .isInstanceOf(Validate.ValidationException.class)
                .hasMessageContaining("phone");
    }

    @Test
    void size_tooShort_fails() {
        assertThatThrownBy(() -> BeanValidator.validate(new SizeRecord("a")))
                .isInstanceOf(Validate.ValidationException.class)
                .hasMessageContaining("code");
    }

    @Test
    void size_tooLong_fails() {
        assertThatThrownBy(() -> BeanValidator.validate(new SizeRecord("abcdefghijk")))
                .isInstanceOf(Validate.ValidationException.class)
                .hasMessageContaining("code");
    }

    @Test
    void size_valid_passes() {
        assertThatCode(() -> BeanValidator.validate(new SizeRecord("hello")))
                .doesNotThrowAnyException();
    }

    @Test
    void notNull_null_fails() {
        assertThatThrownBy(() -> BeanValidator.validate(new NullableRecord(null)))
                .isInstanceOf(Validate.ValidationException.class)
                .hasMessageContaining("ref");
    }

    @Test
    void nonRecord_skipped() {
        assertThatCode(() -> BeanValidator.validate("just a string"))
                .doesNotThrowAnyException();
    }

    @Test
    void multipleViolations_allReported() {
        assertThatThrownBy(() -> BeanValidator.validate(new ValidRecord("", -5)))
                .isInstanceOf(Validate.ValidationException.class)
                .hasMessageContaining("name")
                .hasMessageContaining("age");
    }

    // --- Validatable ---

    record DateRange(String start, String end) implements Validatable {
        public void validate() {
            if (start != null && end != null && end.compareTo(start) < 0)
                throw new Validate.ValidationException("end must be after start");
        }
    }

    record PasswordConfirm(@NotBlank String password, @NotBlank String confirm) implements Validatable {
        public void validate() {
            if (password != null && !password.equals(confirm))
                throw new Validate.ValidationException("passwords do not match");
        }
    }

    @Test
    void validatable_crossField_passes() {
        assertThatCode(() -> BeanValidator.validate(new DateRange("2024-01-01", "2024-12-31")))
                .doesNotThrowAnyException();
    }

    @Test
    void validatable_crossField_fails() {
        assertThatThrownBy(() -> BeanValidator.validate(new DateRange("2024-12-31", "2024-01-01")))
                .isInstanceOf(Validate.ValidationException.class)
                .hasMessageContaining("end must be after start");
    }

    @Test
    void validatable_annotationFailsFirst_validateNotCalled() {
        // @NotBlank fails before validate() — passwords do not match error should NOT appear
        assertThatThrownBy(() -> BeanValidator.validate(new PasswordConfirm("", "other")))
                .isInstanceOf(Validate.ValidationException.class)
                .hasMessageContaining("password")
                .hasMessageNotContaining("do not match");
    }

    @Test
    void validatable_annotationPasses_validateCalled() {
        assertThatThrownBy(() -> BeanValidator.validate(new PasswordConfirm("abc", "xyz")))
                .isInstanceOf(Validate.ValidationException.class)
                .hasMessageContaining("do not match");
    }

    @Test
    void validatable_null_skipped() {
        assertThatCode(() -> BeanValidator.validate(null))
                .doesNotThrowAnyException();
    }
}
