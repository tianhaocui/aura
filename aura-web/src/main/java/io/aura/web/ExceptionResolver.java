package io.aura.web;

import com.alibaba.fastjson2.JSON;
import io.aura.AuraWebRuntime;
import io.aura.Validate;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;

class ExceptionResolver {

    @SuppressWarnings({"unchecked", "rawtypes"})
    static boolean apply(AuraWebRuntime app, Exception e, BaseContext ctx) {
        Throwable cause = unwrap(e);
        if (cause instanceof Error err) throw err;

        for (var entry : app.exceptionHandlers().entrySet()) {
            if (entry.getKey().isAssignableFrom(cause.getClass())) {
                try {
                    ((BaseExceptionHandler) entry.getValue()).handle((Exception) cause, ctx);
                } catch (Exception inner) {
                    org.slf4j.LoggerFactory.getLogger(ExceptionResolver.class)
                            .error("Aura exception handler failed for {}", cause.getClass().getName(), inner);
                    ctx.status(500).json(Map.of("error", "Internal Server Error"));
                }
                return true;
            }
        }

        ExceptionResponse response = resolveDefault(app, cause);
        if (response == null) return false;
        ctx.status(response.status()).json(response.body());
        return true;
    }

    static ExceptionResponse resolveDefault(AuraWebRuntime app, Throwable cause) {
        if (cause instanceof Validate.ValidationException ve && !ve.errors().isEmpty()) {
            return new ExceptionResponse(400, Map.of(
                    "error", "Validation failed",
                    "code", "VALIDATION_ERROR",
                    "hint", "Check the request fields against the API schema",
                    "errors", ve.errors().stream()
                            .map(fe -> Map.of("field", fe.field(), "message", fe.message(),
                                    "reason", "The value does not satisfy the field constraint",
                                    "hint", "Provide a value that satisfies the declared validation rule"))
                            .toList()));
        }
        if (cause instanceof CodedException ce) {
            boolean productionServerError = ce.status() >= 500 && !"dev".equalsIgnoreCase(app.env());
            String message = productionServerError
                    ? "Internal Server Error"
                    : (cause.getMessage() != null ? cause.getMessage() : ce.code());
            return new ExceptionResponse(ce.status(), ApiError.of(message, ce.code(), ce.field(),
                    message, ApiError.hintFor(ce.code())));
        }
        if (cause instanceof IllegalArgumentException || cause instanceof Validate.ValidationException) {
            String message = cause.getMessage() != null ? cause.getMessage() : "Bad Request";
            return new ExceptionResponse(400, ApiError.of(message, "VALIDATION_ERROR", null,
                    message, "Check the request fields against the API schema"));
        }

        if ("dev".equals(app.env())) {
            String msg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
            java.io.StringWriter sw = new java.io.StringWriter();
            cause.printStackTrace(new java.io.PrintWriter(sw));
            return new ExceptionResponse(500, Map.of("error", msg, "code", "INTERNAL_ERROR", "trace", sw.toString()));
        }
        // Unexpected exception details stay in server logs; clients receive a stable response.
        return new ExceptionResponse(500, ApiError.of("Internal Server Error", "INTERNAL_ERROR"));
    }

    static Throwable unwrap(Throwable e) {
        Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
        return cause != null ? cause : e;
    }

    static String code(Throwable cause) {
        return cause instanceof CodedException ce ? ce.code() : null;
    }

    static String field(Throwable cause) {
        return cause instanceof CodedException ce ? ce.field() : null;
    }

    static Integer status(Throwable cause) {
        return cause instanceof CodedException ce ? ce.status() : null;
    }

    record ExceptionResponse(int status, Object body) {
        String json() {
            return JSON.toJSONString(body);
        }
    }
}
