package io.aura;

public record Result<T>(int code, String message, T data) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "ok", data);
    }

    public static <T> Result<T> ok(String message, T data) {
        return new Result<>(0, message, data);
    }

    public static Result<?> fail(int code, String message) {
        return new Result<>(code, message, null);
    }
}
