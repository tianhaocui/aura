package io.aura.web;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import io.aura.Aura;
import io.aura.JsonConfig;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.form.FormData;
import io.undertow.server.handlers.form.FormDataParser;
import io.undertow.server.handlers.form.FormParserFactory;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Context implements BaseContext {

    private final HttpServerExchange exchange;
    private final Map<String, String> pathParams;
    private final Aura app;
    private final String requestId;
    private final Map<Class<?>, Object> attrs = new ConcurrentHashMap<>();
    private final Map<String, Object> namedAttrs = new ConcurrentHashMap<>();
    private String cachedBody;
    private volatile boolean aborted;

    Context(HttpServerExchange exchange, Map<String, String> pathParams, Aura app, String requestId) {
        this.exchange = exchange;
        this.pathParams = pathParams;
        this.app = app;
        this.requestId = requestId;
    }

    public Aura app() { return app; }
    @Override public String requestId() { return requestId; }

    @Override
    public UploadedFile file(String field) throws Exception {
        FormDataParser parser = FormParserFactory.builder().build().createParser(exchange);
        if (parser == null) return null;
        try (parser) {
            FormData formData = parser.parseBlocking();
            FormData.FormValue value = formData.getFirst(field);
            if (value == null || !value.isFileItem()) return null;
            FormData.FileItem item = value.getFileItem();
            byte[] data = item.getInputStream().readAllBytes();
            String contentType = value.getHeaders() != null
                    ? value.getHeaders().getFirst(Headers.CONTENT_TYPE)
                    : null;
            return new UploadedFile(value.getFileName(), data, contentType);
        }
    }

    @Override public String path(String name) { return pathParams.get(name); }

    @Override public String query(String name) {
        Deque<String> values = exchange.getQueryParameters().get(name);
        return values == null || values.isEmpty() ? null : values.peek();
    }

    @Override public String query(String name, String defaultValue) {
        String val = query(name);
        return val == null ? defaultValue : val;
    }

    @Override public String header(String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    @Override public String cookie(String name) {
        var cookie = exchange.getRequestCookie(name);
        return cookie == null ? null : cookie.getValue();
    }

    @Override public <T> T body(Class<T> type) throws IOException {
        if (cachedBody == null) {
            exchange.startBlocking();
            long maxSize = app != null ? app.maxBodySize() : 10 * 1024 * 1024;
            cachedBody = new String(exchange.getInputStream().readNBytes((int) Math.min(maxSize, Integer.MAX_VALUE)), StandardCharsets.UTF_8);
        }
        return JSON.parseObject(cachedBody, type);
    }

    @Override public String method() { return exchange.getRequestMethod().toString(); }
    @Override public String url() { return exchange.getRequestURI(); }
    @Override public int statusCode() { return exchange.getStatusCode(); }

    @Override public Context status(int code) { exchange.setStatusCode(code); return this; }

    @Override public void json(Object obj) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        JsonConfig cfg = app != null ? app.jsonConfig() : null;
        String dateFormat = cfg != null ? cfg.dateFormat() : "yyyy-MM-dd'T'HH:mm:ss.SSS";
        JSONWriter.Feature[] features = cfg != null && cfg.writeNulls()
                ? new JSONWriter.Feature[]{JSONWriter.Feature.WriteNulls}
                : new JSONWriter.Feature[0];
        exchange.getResponseSender().send(JSON.toJSONString(obj, dateFormat, features));
    }

    @Override public void text(String text) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain; charset=utf-8");
        exchange.getResponseSender().send(text);
    }

    @Override public void redirect(String url) {
        if (url.indexOf('\r') >= 0 || url.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid redirect URL");
        }
        exchange.setStatusCode(302);
        exchange.getResponseHeaders().put(Headers.LOCATION, url);
        exchange.endExchange();
    }

    @Override public Context header(String name, String value) {
        exchange.getResponseHeaders().put(new HttpString(name), value);
        return this;
    }

    @Override public Context cookie(String name, String value, int maxAge) {
        var cookie = new io.undertow.server.handlers.CookieImpl(name, value);
        cookie.setMaxAge(maxAge);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setSameSiteMode("Lax");
        exchange.setResponseCookie(cookie);
        return this;
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

    @Override
    public SseEmitter sse() {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().put(new HttpString("Cache-Control"), "no-cache");
        exchange.getResponseHeaders().put(new HttpString("X-Accel-Buffering"), "no");
        exchange.setPersistent(false);
        exchange.startBlocking();
        var out = exchange.getOutputStream();
        return new SseEmitter() {
            @Override
            public void send(String data) throws Exception {
                write(formatData(data));
            }

            @Override
            public void send(String event, String data) throws Exception {
                write("event: " + sanitize(event) + "\n" + formatData(data));
            }

            @Override
            public void send(String event, String data, String id) throws Exception {
                write("id: " + sanitize(id) + "\nevent: " + sanitize(event) + "\n" + formatData(data));
            }

            @Override
            public void close() {
                try { out.close(); } catch (IOException ignored) {}
            }

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

            private void write(String text) throws IOException {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        };
    }

    @Override
    public void abort() { this.aborted = true; }

    @Override
    public boolean isAborted() { return aborted; }
}

