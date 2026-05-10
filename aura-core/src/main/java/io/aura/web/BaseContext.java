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
}
