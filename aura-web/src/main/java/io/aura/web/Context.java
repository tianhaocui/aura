package io.aura.web;

import com.alibaba.fastjson2.JSON;
import io.aura.Aura;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.form.FormData;
import io.undertow.server.handlers.form.FormDataParser;
import io.undertow.server.handlers.form.FormParserFactory;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Context implements BaseContext {

    private final HttpServerExchange exchange;
    private final Map<String, String> pathParams;
    private final Aura app;
    private final Map<Class<?>, Object> attrs = new ConcurrentHashMap<>();
    private final Map<String, Object> namedAttrs = new ConcurrentHashMap<>();
    private String cachedBody;

    Context(HttpServerExchange exchange, Map<String, String> pathParams, Aura app) {
        this.exchange = exchange;
        this.pathParams = pathParams;
        this.app = app;
    }

    public Aura app() { return app; }

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
        exchange.getResponseSender().send(JSON.toJSONString(obj));
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
}

