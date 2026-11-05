package io.aura.web;

import io.aura.port.BaseContext;
import io.aura.port.BaseExceptionHandler;
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

        BaseExceptionHandler<?> handler = null;
        Class<?> handlerType = null;
        for (var entry : app.exceptionHandlers().entrySet()) {
            if (!entry.getKey().isAssignableFrom(cause.getClass())) continue;
            // Most-specific wins. The registry is a hash map, so without this a handler for a
            // superclass could shadow one registered for the exact exception on any given run.
            if (handlerType == null || handlerType.isAssignableFrom(entry.getKey())) {
                handlerType = entry.getKey();
                handler = entry.getValue();
            }
        }
        if (handler != null) {
            try {
                ((BaseExceptionHandler) handler).handle((Exception) cause, ctx);
            } catch (Exception inner) {
                org.slf4j.LoggerFactory.getLogger(ExceptionResolver.class)
                        .error("Aura exception handler failed for {}", cause.getClass().getName(), inner);
                ctx.status(500).json(Map.of("error", "Internal Server Error"));
            }
            return true;
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
        if (cause instanceof Validate.ValidationException) {
            // Validation messages describe the caller's input, not the application's internals;
            // they are part of the contract and stay visible.
            String message = cause.getMessage() != null ? cause.getMessage() : "Validation failed";
            return new ExceptionResponse(400, ApiError.of(message, "VALIDATION_ERROR", null,
                    message, "Check the request fields against the API schema"));
        }
        if (cause instanceof IllegalArgumentException) {
            // A raw IllegalArgumentException message is an implementation detail: it can name a
            // table, a class, or an internal limit. The caller gets the contract, the log keeps
            // the detail.
            return new ExceptionResponse(400, ApiError.of("Bad Request", "VALIDATION_ERROR", null,
                    "The request could not be processed", "Check the request fields and format against the API schema"));
        }

        // A body this application will not read is the caller's mistake, and a 500 answers it with
        // an invitation to send the same oversized request again. Undertow raises its own type when
        // the configured entity limit is hit mid-stream, so both limits answer the same way.
        if (cause instanceof RequestBodyTooLargeException
                || cause instanceof io.undertow.server.RequestTooBigException) {
            String message = "Request body too large";
            return new ExceptionResponse(413, ApiError.of(message, "REQUEST_BODY_TOO_LARGE", null,
                    message, "Send a body that fits the maximum size this service documents"));
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
