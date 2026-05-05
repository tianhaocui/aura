package io.aura.web;

import com.alibaba.fastjson2.JSON;
import io.aura.Aura;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class MockContext extends Context {

    int status;
    String responseBody;
    private final Map<String, String> pathParams;
    private final Map<String, String> headers;
    private final Map<String, String> queryParams;
    private final String body;
    private final Aura app;
    private final Map<Class<?>, Object> attrs = new ConcurrentHashMap<>();

    MockContext(Map<String, String> pathParams, Map<String, String> queryParams,
                Map<String, String> headers, String body, Aura app) {
        super(null, pathParams, app);
        this.pathParams = pathParams;
        this.headers = headers;
        this.body = body;
        this.app = app;
        this.queryParams = queryParams;
    }

    @Override public String path(String name) { return pathParams.get(name); }
    @Override public String query(String name) { return queryParams.get(name); }
    @Override public String query(String name, String def) { String v = query(name); return v != null ? v : def; }
    @Override public String header(String name) { return headers.get(name); }
    @Override public String cookie(String name) { return null; }
    @Override public <T> T body(Class<T> type) { return JSON.parseObject(body, type); }
    @Override public String method() { return ""; }
    @Override public String url() { return ""; }

    @Override public Context status(int code) { this.status = code; return this; }
    @Override public void json(Object obj) { responseBody = JSON.toJSONString(obj); }
    @Override public void text(String text) { responseBody = text; }
    @Override public void redirect(String url) { status = 302; }
    @Override public Context header(String name, String value) { return this; }
    @Override public Context cookie(String name, String value, int maxAge) { return this; }

    @Override public <T> void set(T instance) { attrs.put(instance.getClass(), instance); }
    @Override @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type) {
        for (var entry : attrs.entrySet()) {
            if (type.isAssignableFrom(entry.getKey())) return (T) entry.getValue();
        }
        return null;
    }
    @Override public Aura app() { return app; }
}
