package io.aura;

public final class Validate {

    public static void notNull(Object value, String message) {
        if (value == null) throw new ValidationException(message);
    }

    public static void notBlank(String value, String message) {
        if (value == null || value.isBlank()) throw new ValidationException(message);
    }

    public static void range(int value, int min, int max, String message) {
        if (value < min || value > max) throw new ValidationException(message);
    }

    public static void range(long value, long min, long max, String message) {
        if (value < min || value > max) throw new ValidationException(message);
    }

    public static void minLength(String value, int min, String message) {
        if (value == null || value.length() < min) throw new ValidationException(message);
    }

    public static void maxLength(String value, int max, String message) {
        if (value != null && value.length() > max) throw new ValidationException(message);
    }

    public static void matches(String value, String regex, String message) {
        if (value == null || !value.matches(regex)) throw new ValidationException(message);
    }

    public static void isTrue(boolean condition, String message) {
        if (!condition) throw new ValidationException(message);
    }

    public static class ValidationException extends RuntimeException {
        public ValidationException(String message) { super(message); }
    }

    private Validate() {}
}
