package io.aura.web;

public record ApiError(String error, String code, String field) {

    public static ApiError of(String error, String code) {
        return new ApiError(error, code, null);
    }

    public static ApiError of(String error, String code, String field) {
        return new ApiError(error, code, field);
    }
}
