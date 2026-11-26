package io.aura.web;

public class ApiException extends RuntimeException implements CodedException {

    private final int status;
    private final String code;
    private final String field;

    public ApiException(String code, String message) {
        this(400, code, message, null, null);
    }

    public ApiException(String code, String message, String field) {
        this(400, code, message, field, null);
    }

    public ApiException(int status, String code, String message) {
        this(status, code, message, null, null);
    }

    public ApiException(int status, String code, String message, String field) {
        this(status, code, message, field, null);
    }

    public ApiException(int status, String code, String message, String field, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
        this.field = field;
    }

    // Named doors for the responses an application raises on purpose. The status and the code
    // travel together from one place, so a handler cannot ship a 401 through a 400 constructor;
    // a caller that needs a field name or a cause keeps using the constructors above.
    public static ApiException badRequest(String code, String message) {
        return new ApiException(400, code, message);
    }

    public static ApiException unauthorized(String code, String message) {
        return new ApiException(401, code, message);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(403, code, message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(404, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }

    public static ApiException locked(String code, String message) {
        return new ApiException(423, code, message);
    }

    @Override public int status() { return status; }
    @Override public String code() { return code; }
    @Override public String field() { return field; }
}
