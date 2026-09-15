package io.aura.web;

import com.alibaba.fastjson2.JSON;
import io.aura.AuraWebRuntime;
import io.aura.snapshot.Snapshot;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MockContext extends Context {

    int status;
    String responseBody;
    private boolean responded;
    private final Map<String, String> pathParams;
    private final Map<String, String> headers;
    private final Map<String, String> queryParams;
    private final String body;
    private final AuraWebRuntime app;
    private final String requestPath;
    private final Map<Class<?>, Object> attrs = new ConcurrentHashMap<>();
    private final Map<String, Object> namedAttrs = new ConcurrentHashMap<>();
    private final Map<String, String> respHeaders = new HashMap<>();

    public MockContext(Map<String, String> pathParams, Map<String, String> queryParams,
                       Map<String, String> headers, String body, AuraWebRuntime app) {
        this(pathParams, queryParams, headers, body, app, "");
    }

    public MockContext(Map<String, String> pathParams, Map<String, String> queryParams,
                       Map<String, String> headers, String body, AuraWebRuntime app, String requestPath) {
        super(null, pathParams, app, null);
        this.pathParams = pathParams;
        this.headers = headers;
        this.body = body;
        this.app = app;
        this.queryParams = queryParams;
        this.requestPath = requestPath;
        if (app != null) namedAttrs.put("_app", app);
    }

    @Override public String path(String name) { return pathParams.get(name); }
    @Override public String query(String name) { return queryParams.get(name); }
    @Override public String query(String name, String def) { String v = query(name); return v != null ? v : def; }
    @Override public String header(String name) { return headers.get(name); }
    @Override public String cookie(String name) { return null; }
    @Override public <T> T body(Class<T> type) { return body((java.lang.reflect.Type) type); }
    @Override @SuppressWarnings("unchecked")
    public <T> T body(java.lang.reflect.Type type) {
        return body == null || body.isBlank() ? null : (T) JSON.parseObject(body, type);
    }
    @Override public String method() { return ""; }
    @Override public String url() { return requestPath; }
    @Override public int statusCode() { return status == 0 ? 200 : status; }

    @Override public Context status(int code) { this.status = code; return this; }
    @Override public void json(Object obj) {
        io.aura.JsonConfig cfg = app != null ? app.jsonConfig() : null;
        String dateFormat = cfg != null ? cfg.dateFormat() : "yyyy-MM-dd'T'HH:mm:ss.SSS";
        com.alibaba.fastjson2.JSONWriter.Feature[] features = cfg != null && cfg.writeNulls()
                ? new com.alibaba.fastjson2.JSONWriter.Feature[]{com.alibaba.fastjson2.JSONWriter.Feature.WriteNulls}
                : new com.alibaba.fastjson2.JSONWriter.Feature[0];
        responseBody = JSON.toJSONString(obj, dateFormat, features);
        Snapshot.response(statusCode(), "application/json; charset=utf-8", obj);
        responded = true;
    }
    @Override public void jsonRaw(String json) { responseBody = json; Snapshot.response(statusCode(), "application/json; charset=utf-8", json); responded = true; }
    @Override public void text(String text) { responseBody = text; Snapshot.response(statusCode(), "text/plain; charset=utf-8", text); responded = true; }
    @Override public void html(String html) { responseBody = html; Snapshot.response(statusCode(), "text/html; charset=utf-8", html); responded = true; }
    @Override public void raw(String body) { responseBody = body; Snapshot.response(statusCode(), null, body); responded = true; }
    @Override public void redirect(String url) { status = 302; Snapshot.response(statusCode(), null, null); responded = true; }
    @Override public Context header(String name, String value) { respHeaders.put(name, value); return this; }
    @Override public Context cookie(String name, String value, int maxAge) { return this; }

    @Override public boolean isResponseStarted() { return responded; }
    Map<String, String> responseHeaders() { return respHeaders; }

    byte[] fileBytes;
    String fileName;
    String fileContentType;

    @Override public void sendFile(String filename, byte[] data, String contentType) {
        this.fileName = filename;
        this.fileBytes = data;
        this.fileContentType = contentType;
        Snapshot.response(statusCode(), contentType, java.util.Map.of("filename", filename, "bytes", data.length));
    }

    @Override public <T> void set(T instance) { attrs.put(instance.getClass(), instance); }
    @Override @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type) {
        for (var entry : attrs.entrySet()) {
            if (type.isAssignableFrom(entry.getKey())) return (T) entry.getValue();
        }
        return null;
    }
    @Override public void set(String key, Object value) { namedAttrs.put(key, value); }
    @Override @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) { return (T) namedAttrs.get(key); }
    @Override public AuraWebRuntime app() { return app; }

    @Override
    public String ip() {
        String xff = headers.get("X-Forwarded-For");
        String trusted = app != null ? app.prop("aura.trusted-proxies") : null;
        return Context.resolveClientIp(trusted, "127.0.0.1", xff);
    }

    @Override
    public SseEmitter sse() {
        return new SseEmitter() {
            @Override public void send(String data) {
                append(formatData(data));
            }
            @Override public void send(String event, String data) {
                append("event: " + sanitize(event) + "\n" + formatData(data));
            }
            @Override public void send(String event, String data, String id) {
                append("id: " + sanitize(id) + "\nevent: " + sanitize(event) + "\n" + formatData(data));
            }
            @Override public void close() {}

            private String formatData(String data) {
                StringBuilder sb = new StringBuilder();
                for (String line : data.split("\n", -1)) {
                    sb.append("data: ").append(line).append("\n");
                }
                sb.append("\n");
                return sb.toString();
            }

            private String sanitize(String value) {
                return value.replaceAll("[\\r\\n]", "");
            }

            private void append(String chunk) {
                responseBody = responseBody == null ? chunk : responseBody + chunk;
            }
        };
    }
}
