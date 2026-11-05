package io.aura.web;

import io.aura.port.SseEmitter;
import com.alibaba.fastjson2.JSON;
import io.aura.AuraWebRuntime;
import io.aura.snapshot.Snapshot;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MockContext extends Context {

    /** The address an in-memory request is deemed to have arrived from. */
    private static final String LOCALHOST = "127.0.0.1";

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
    private final String peerIp;
    private final String httpMethod;
    private final java.util.Map<String, String> responseCookies = new java.util.LinkedHashMap<>();

    public MockContext(Map<String, String> pathParams, Map<String, String> queryParams,
                       Map<String, String> headers, String body, AuraWebRuntime app) {
        this(pathParams, queryParams, headers, body, app, "");
    }

    public MockContext(Map<String, String> pathParams, Map<String, String> queryParams,
                       Map<String, String> headers, String body, AuraWebRuntime app, String requestPath) {
        this(pathParams, queryParams, headers, body, app, requestPath, LOCALHOST);
    }

    public MockContext(Map<String, String> pathParams, Map<String, String> queryParams,
                       Map<String, String> headers, String body, AuraWebRuntime app,
                       String requestPath, String peerIp) {
        this(pathParams, queryParams, headers, body, app, requestPath, peerIp, "");
    }

    /**
     * The method is the one thing an in-memory request cannot infer from the route it matched, and
     * a handler that branches on {@code ctx.method()} has to branch the same way here as it does
     * behind a socket.
     */
    MockContext(Map<String, String> pathParams, Map<String, String> queryParams,
                Map<String, String> headers, String body, AuraWebRuntime app,
                String requestPath, String peerIp, String httpMethod) {
        super(null, pathParams, app, null);
        this.peerIp = peerIp;
        this.pathParams = pathParams;
        this.headers = headers;
        this.body = body;
        this.app = app;
        this.queryParams = queryParams;
        this.requestPath = requestPath;
        this.httpMethod = httpMethod == null ? "" : httpMethod;
        if (app != null) namedAttrs.put("_app", app);
    }

    @Override public String path(String name) { return pathParams.get(name); }
    @Override public String query(String name) { return queryParams.get(name); }
    @Override public String query(String name, String def) { String v = query(name); return v != null ? v : def; }
    @Override public String header(String name) {
        for (var entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }
    @Override public String cookie(String name) {
        String header = header("Cookie");
        if (header == null) return null;
        for (String item : header.split(";")) {
            String[] pair = item.trim().split("=", 2);
            if (pair.length == 2 && pair[0].trim().equals(name)) return pair[1].trim();
        }
        return null;
    }
    @Override public String bodyText() { return body; }
    @Override public <T> T body(Class<T> type) { return body((java.lang.reflect.Type) type); }
    @Override @SuppressWarnings("unchecked")
    public <T> T body(java.lang.reflect.Type type) {
        return body == null || body.isBlank() ? null : (T) JSON.parseObject(body, type);
    }
    @Override public String method() { return httpMethod; }
    @Override public String url() { return requestPath; }
    @Override public int statusCode() { return status == 0 ? 200 : status; }

    @Override public Context status(int code) {
        if (responded) return this;
        this.status = code;
        return this;
    }
    @Override public void json(Object obj) {
        if (responded || isAborted()) return;
        header("Content-Type", "application/json; charset=utf-8");
        io.aura.JsonConfig cfg = app != null ? app.jsonConfig() : null;
        String dateFormat = cfg != null ? cfg.dateFormat() : "yyyy-MM-dd'T'HH:mm:ss.SSS";
        com.alibaba.fastjson2.JSONWriter.Feature[] features = cfg != null && cfg.writeNulls()
                ? new com.alibaba.fastjson2.JSONWriter.Feature[]{com.alibaba.fastjson2.JSONWriter.Feature.WriteNulls}
                : new com.alibaba.fastjson2.JSONWriter.Feature[0];
        responseBody = JSON.toJSONString(obj, dateFormat, features);
        Snapshot.response(statusCode(), "application/json; charset=utf-8", obj);
        responded = true;
    }
    @Override public void jsonRaw(String json) {
        if (responded || isAborted()) return;
        header("Content-Type", "application/json; charset=utf-8");
        responseBody = json; Snapshot.response(statusCode(), "application/json; charset=utf-8", json); responded = true;
    }
    @Override public void text(String text) {
        if (responded || isAborted()) return;
        header("Content-Type", "text/plain; charset=utf-8");
        responseBody = text; Snapshot.response(statusCode(), "text/plain; charset=utf-8", text); responded = true;
    }
    @Override public void html(String html) {
        if (responded || isAborted()) return;
        header("Content-Type", "text/html; charset=utf-8");
        responseBody = html; Snapshot.response(statusCode(), "text/html; charset=utf-8", html); responded = true;
    }
    @Override public void raw(String body) {
        if (responded || isAborted()) return;
        responseBody = body; Snapshot.response(statusCode(), null, body); responded = true;
    }
    @Override public void redirect(String url) {
        if (responded || isAborted()) return;
        // A redirect without its Location header is not a redirect: the client has nothing to
        // follow, and a test that only checks 302 would pass against a broken handler.
        if (url.indexOf('\r') >= 0 || url.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid redirect URL");
        }
        status = 302;
        header("Location", url);
        Snapshot.response(statusCode(), null, null);
        responded = true;
    }
    @Override public Context header(String name, String value) {
        if (responded || isAborted()) return this;
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Header name must not be blank");
        if (name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid header name");
        }
        if (value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)) {
            throw new IllegalArgumentException("Invalid header value");
        }
        respHeaders.entrySet().removeIf(entry -> entry.getKey().equalsIgnoreCase(name));
        respHeaders.put(name, value);
        return this;
    }
    @Override public Context cookie(String name, String value, int maxAge) {
        if (isAborted() || responded) return this;
        StringBuilder setCookie = new StringBuilder(name).append('=').append(value == null ? "" : value)
                .append("; Path=/; HttpOnly; Secure; SameSite=Lax");
        if (maxAge >= 0) setCookie.append("; Max-Age=").append(maxAge);
        responseCookies.put(name, setCookie.toString());
        header("Set-Cookie", setCookie.toString());
        return this;
    }

    @Override public boolean isResponseStarted() { return responded; }
    Map<String, String> responseHeaders() { return respHeaders; }
    java.util.List<String> responseCookies() {
        return java.util.List.copyOf(responseCookies.values());
    }

    byte[] fileBytes;
    String fileName;
    String fileContentType;

    @Override public void sendFile(String filename, byte[] data, String contentType) {
        if (responded || isAborted()) return;
        this.fileName = filename;
        this.fileBytes = data;
        this.fileContentType = contentType;
        // The download is announced by these two headers, so a test that never sees them cannot
        // tell a served file from a blob, and the name is sanitised exactly as the socket does.
        header("Content-Type", contentType);
        header("Content-Disposition",
                "attachment; filename=\"" + filename.replaceAll("[\\r\\n\"]", "") + "\"");
        responded = true;
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
        String trusted = app == null ? null : String.join(",", app.trustedProxies());
        // The peer is the address the guard chain judged and the lookup is the
        // case-insensitive one HTTP uses. Two client identities inside one mock
        // request means a test that exercises the code nobody ships.
        return Context.resolveClientIp(trusted, peerIp, header("X-Forwarded-For"));
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
