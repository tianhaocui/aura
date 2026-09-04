package io.aura.web;

public record ApiError(String error, String code, String field, String reason, String hint) {

    public static ApiError of(String error, String code) {
        return of(error, code, null);
    }

    public static ApiError of(String error, String code, String field) {
        return new ApiError(error, code, field, error, hintFor(code));
    }

    public static ApiError of(String error, String code, String field, String reason, String hint) {
        return new ApiError(error, code, field, reason, hint);
    }

    static String hintFor(String code) {
        if (code == null) return null;
        if (code.startsWith("VALIDATION") || "VALIDATION_ERROR".equals(code)) {
            return "Check the request fields against the API schema";
        }
        if (code.startsWith("AUTH")) return "Authenticate the request and retry";
        if (code.startsWith("FORBIDDEN") || code.startsWith("PERMISSION")) {
            return "Request the required permission or use an authorized identity";
        }
        return null;
    }
}
