package io.aura.port;

public interface BaseContext {

    /** Returns a required scalar parameter or rejects the request the way reflective binding does. */
    static String requireParameter(String value, String name) {
        if (value == null) throw new io.aura.Validate.ValidationException("Missing required parameter: " + name);
        return value;
    }

    static String requireCharacter(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new io.aura.Validate.ValidationException("Missing required parameter: " + name);
        }
        return value;
    }

    // --- request ---
    String path(String name);
    String query(String name);
    String query(String name, String defaultValue);
    String header(String name);
    String cookie(String name);
    /** Returns the raw request body when the adapter can expose it safely. */
    default String bodyText() throws Exception {
        throw new UnsupportedOperationException("Raw body access is not supported in this context");
    }
    <T> T body(Class<T> type) throws Exception;
    /** Decodes a body using a generic Java reflection type such as List<Order>. */
    @SuppressWarnings("unchecked")
    default <T> T body(java.lang.reflect.Type type) throws Exception {
        if (type instanceof Class<?> clazz) return (T) body(clazz);
        throw new UnsupportedOperationException("Generic body decoding is not supported in this context");
    }
    String method();
    String url();
    int statusCode();

    /** Whether a handler or middleware has already committed the response. */
    default boolean isResponseStarted() { return false; }

    /** Runtime view associated with this request, when one is available. */
    default io.aura.AuraWebRuntime app() { return null; }

    // --- response ---
    BaseContext status(int code);
    void json(Object obj);
    void jsonRaw(String json);
    void text(String text);
    void html(String html);
    void raw(String body);
    void redirect(String url);
    BaseContext header(String name, String value);
    BaseContext cookie(String name, String value, int maxAge);

    // --- attributes ---
    <T> void set(T instance);
    <T> T get(Class<T> type);
    void set(String key, Object value);
    <T> T get(String key, Class<T> type);

    // --- body with validation ---
    default <T> T bodyOrThrow(Class<T> type) throws Exception {
        T obj = body(type);
        if (obj == null) throw new io.aura.Validate.ValidationException("Request body is required");
        io.aura.BeanValidator.validate(obj);
        return obj;
    }

    default <T> T bodyOrThrow(java.lang.reflect.Type type) throws Exception {
        T obj = body(type);
        if (obj == null) throw new io.aura.Validate.ValidationException("Request body is required");
        io.aura.BeanValidator.validate(obj);
        return obj;
    }

    // --- required params ---
    default String queryRequired(String name) {
        String v = query(name);
        if (v == null || v.isBlank()) throw new io.aura.Validate.ValidationException("Missing required query parameter: " + name);
        return v;
    }

    default int pathInt(String name) {
        String v = path(name);
        if (v == null || v.isBlank()) throw new io.aura.Validate.ValidationException("Missing path parameter: " + name);
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) {
            throw new io.aura.Validate.ValidationException("Path parameter '" + name + "' must be an integer, got: " + v);
        }
    }

    default long pathLong(String name) {
        String v = path(name);
        if (v == null || v.isBlank()) throw new io.aura.Validate.ValidationException("Missing path parameter: " + name);
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) {
            throw new io.aura.Validate.ValidationException("Path parameter '" + name + "' must be a long, got: " + v);
        }
    }

    // --- typed query params ---
    default int queryInt(String name, int defaultValue) {
        String v = query(name);
        if (v == null || v.isBlank()) return defaultValue;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return defaultValue; }
    }

    default long queryLong(String name, long defaultValue) {
        String v = query(name);
        if (v == null || v.isBlank()) return defaultValue;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return defaultValue; }
    }

    default boolean queryBool(String name, boolean defaultValue) {
        String v = query(name);
        if (v == null || v.isBlank()) return defaultValue;
        return "true".equalsIgnoreCase(v.trim());
    }

    // --- pagination helpers ---
    default int pageNum() {
        String v = query("page");
        if (v == null || v.isBlank()) return 1;
        try {
            int page = Integer.parseInt(v.trim());
            if (page < 1) throw new io.aura.Validate.ValidationException("page must be positive");
            return page;
        } catch (NumberFormatException e) { throw new io.aura.Validate.ValidationException("page must be an integer"); }
    }

    default int pageSize() {
        String v = query("pageSize");
        if (v == null || v.isBlank()) return 20;
        try {
            int size = Integer.parseInt(v.trim());
            int max = 500;
            Object configuredApp = get("_app", Object.class);
            if (configuredApp instanceof io.aura.AuraWebRuntime app) max = app.config().pageSizeMax();
            if (size < 1 || size > max) throw new io.aura.Validate.ValidationException("pageSize must be between 1 and " + max);
            return size;
        } catch (NumberFormatException e) { throw new io.aura.Validate.ValidationException("pageSize must be an integer"); }
    }

    // --- file upload ---
    default UploadedFile file(String field) throws Exception { return null; }
    default String formField(String name) throws Exception { return null; }

    // --- file download ---
    default void sendFile(String filename, byte[] data) throws Exception {
        sendFile(filename, data, "application/octet-stream");
    }
    default void sendFile(String filename, byte[] data, String contentType) throws Exception {
        throw new UnsupportedOperationException("sendFile not supported in this context");
    }

    // --- auth ---
    default String userId() {
        Object id = get("_userId", Object.class);
        if (id == null) throw new IllegalStateException(
                "Not authenticated - configure AuraApp.authenticate(...) and route authorization");
        return id.toString();
    }

    // --- SSE ---
    default SseEmitter sse() throws Exception {
        throw new UnsupportedOperationException("SSE not supported in this context");
    }

    // --- flow control ---
    void abort();
    boolean isAborted();

    // --- request id ---
    default String requestId() { return null; }

    // --- client ip ---
    default String ip() { return null; }

    /** Cooperative cancellation state for timeout, disconnect, and application shutdown. */
    default io.aura.contract.CancellationToken cancellationToken() { return null; }
    default boolean isCancelled() {
        io.aura.contract.CancellationToken token = cancellationToken();
        return token != null && token.isCancelled();
    }
}
