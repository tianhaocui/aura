package io.aura.web;

public interface BaseContext {

    // --- request ---
    String path(String name);
    String query(String name);
    String query(String name, String defaultValue);
    String header(String name);
    String cookie(String name);
    <T> T body(Class<T> type) throws Exception;
    String method();
    String url();
    int statusCode();

    // --- response ---
    BaseContext status(int code);
    void json(Object obj);
    void text(String text);
    void redirect(String url);
    BaseContext header(String name, String value);
    BaseContext cookie(String name, String value, int maxAge);

    // --- attributes ---
    <T> void set(T instance);
    <T> T get(Class<T> type);
    void set(String key, Object value);
    <T> T get(String key, Class<T> type);

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
        try { return Math.max(1, Integer.parseInt(v.trim())); } catch (NumberFormatException e) { return 1; }
    }

    default int pageSize() {
        String v = query("pageSize");
        if (v == null || v.isBlank()) return 20;
        try { return Math.max(1, Math.min(500, Integer.parseInt(v.trim()))); } catch (NumberFormatException e) { return 20; }
    }

    // --- file upload ---
    default UploadedFile file(String field) throws Exception { return null; }

    // --- file download ---
    default void sendFile(String filename, byte[] data) throws Exception {
        sendFile(filename, data, "application/octet-stream");
    }
    default void sendFile(String filename, byte[] data, String contentType) throws Exception {
        throw new UnsupportedOperationException("sendFile not supported in this context");
    }

    // --- auth ---
    default long userId() {
        Object id = get("_userId", Object.class);
        if (id == null) throw new IllegalStateException("Not authenticated — use Aura.requireAuth() middleware");
        return id instanceof Long l ? l : Long.parseLong(id.toString());
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
}
