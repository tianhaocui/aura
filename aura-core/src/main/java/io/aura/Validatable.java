package io.aura;

/**
 * Implement on a request record/POJO to add cross-field validation logic.
 * Called automatically after annotation-based validation passes.
 *
 * <pre>{@code
 * record DateRange(
 *     @NotNull LocalDate start,
 *     @NotNull LocalDate end
 * ) implements Validatable {
 *     public void validate() {
 *         Validate.isTrue(!end.isBefore(start), "end must be after start");
 *     }
 * }
 * }</pre>
 */
public interface Validatable {
    void validate();
}
