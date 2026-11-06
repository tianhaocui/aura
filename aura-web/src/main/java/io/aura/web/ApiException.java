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

    @Override public int status() { return status; }
    @Override public String code() { return code; }
    @Override public String field() { return field; }
}
