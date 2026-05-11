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
}
