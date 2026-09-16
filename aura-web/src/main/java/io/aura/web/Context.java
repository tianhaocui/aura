package io.aura.web;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import io.aura.AuraWebRuntime;
import io.aura.contract.CancellationToken;
import io.aura.JsonConfig;
import io.aura.snapshot.Snapshot;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.form.FormData;
import io.undertow.server.handlers.form.FormDataParser;
import io.undertow.server.handlers.form.FormParserFactory;
import io.undertow.server.handlers.form.FormEncodedDataDefinition;
import io.undertow.server.handlers.form.MultiPartParserDefinition;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Context implements BaseContext {

    private final HttpServerExchange exchange;
    private final Map<String, String> pathParams;
    private final AuraWebRuntime app;
    private final String requestId;
    private final Map<Class<?>, Object> attrs = new ConcurrentHashMap<>();
    private final Map<String, Object> namedAttrs = new ConcurrentHashMap<>();
    private String cachedBody;
    private FormDataParser cachedFormParser;
    private FormData cachedFormData;
    private volatile boolean aborted;
    private final CancellationToken cancellationToken;

    Context(HttpServerExchange exchange, Map<String, String> pathParams, AuraWebRuntime app, String requestId) {
        this(exchange, pathParams, app, requestId, new CancellationToken());
    }

    Context(HttpServerExchange exchange, Map<String, String> pathParams, AuraWebRuntime app, String requestId, CancellationToken cancellationToken) {
        this.exchange = exchange;
        this.pathParams = pathParams;
        this.app = app;
        this.requestId = requestId;
        this.cancellationToken = cancellationToken;
        if (app != null) namedAttrs.put("_app", app);
    }

    public AuraWebRuntime app() { return app; }
    @Override public boolean isResponseStarted() { return exchange != null && exchange.isResponseStarted(); }
    String cachedBody() { return cachedBody; }
    @Override public String requestId() { return requestId; }
    @Override public CancellationToken cancellationToken() { return cancellationToken; }

    @Override
    public UploadedFile file(String field) throws Exception {
        FormData formData = formData();
        if (formData == null) return null;
        FormData.FormValue value = formData.getFirst(field);
        if (value == null || !value.isFileItem()) return null;
        FormData.FileItem item = value.getFileItem();
        long max = uploadLimit();
        int limit = (int) Math.min(max, Integer.MAX_VALUE - 1L);
        byte[] data;
        try (var input = item.getInputStream()) {
            data = input.readNBytes(limit + 1);
        }
        if (data.length > limit) throw new IOException("Multipart file exceeds maximum upload size");
        String contentType = value.getHeaders() != null
                ? value.getHeaders().getFirst(Headers.CONTENT_TYPE)
                : null;
        return new UploadedFile(value.getFileName(), data, contentType);
    }

    @Override
    public String formField(String name) throws Exception {
        FormData formData = formData();
        if (formData == null) return null;
        FormData.FormValue value = formData.getFirst(name);
        if (value == null || value.isFileItem()) return null;
        return value.getValue();
    }

    private FormData formData() throws IOException {
        if (cachedFormData != null) return cachedFormData;
        exchange.startBlocking();
        cachedFormParser = createFormParser();
        if (cachedFormParser == null) return null;
        try {
            cachedFormData = cachedFormParser.parseBlocking();
            exchange.addExchangeCompleteListener((completed, next) -> {
                try { cachedFormParser.close(); } catch (IOException ignored) {}
                finally { next.proceed(); }
            });
            return cachedFormData;
        } catch (IOException e) {
            try { cachedFormParser.close(); } catch (IOException ignored) {}
            cachedFormParser = null;
            throw e;
        }
    }

    private FormDataParser createFormParser() {
        long max = uploadLimit();
        MultiPartParserDefinition multipart = new MultiPartParserDefinition();
        multipart.setMaxIndividualFileSize(max);
        multipart.setFieldSizeThreshold(Math.min(max, 1024 * 1024));
        return FormParserFactory.builder(false)
                .addParser(new FormEncodedDataDefinition())
                .addParser(multipart)
                .build().createParser(exchange);
    }

    private long uploadLimit() {
        long uploadMax = app != null ? app.maxUploadSize() : 1024 * 1024L;
        long bodyMax = app != null ? app.maxBodySize() : 10 * 1024 * 1024L;
        if (bodyMax > 0) uploadMax = Math.min(uploadMax, bodyMax);
        return Math.max(0L, uploadMax);
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
        return body((java.lang.reflect.Type) type);
    }

    @Override public String bodyText() throws IOException {
        if (cachedBody == null) {
            exchange.startBlocking();
            long maxSize = app != null ? app.maxBodySize() : 10 * 1024 * 1024L;
            cachedBody = readBody(exchange.getInputStream(), maxSize);
        }
        return cachedBody;
    }

    @Override @SuppressWarnings("unchecked")
    public <T> T body(java.lang.reflect.Type type) throws IOException {
        if (cachedBody == null) {
            exchange.startBlocking();
            long maxSize = app != null ? app.maxBodySize() : 10 * 1024 * 1024L;
            cachedBody = readBody(exchange.getInputStream(), maxSize);
        }
        if (cachedBody.isBlank()) return null;
        return (T) JSON.parseObject(cachedBody, type);
    }

    private static String readBody(java.io.InputStream input, long maxSize) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (maxSize > 0 && total > maxSize) throw new IOException("Request body exceeds maximum size");
            if (total > Integer.MAX_VALUE) throw new IOException("Request body exceeds maximum supported size");
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Override public String method() { return exchange.getRequestMethod().toString(); }
    @Override public String url() { return exchange.getRequestURI(); }
    @Override public int statusCode() { return exchange.getStatusCode(); }

    @Override public Context status(int code) {
        if (!isResponseStarted()) exchange.setStatusCode(code);
        return this;
    }

    @Override public void json(Object obj) {
        if (aborted || isResponseStarted()) return;
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        JsonConfig cfg = app != null ? app.jsonConfig() : null;
        String dateFormat = cfg != null ? cfg.dateFormat() : "yyyy-MM-dd'T'HH:mm:ss.SSS";
        JSONWriter.Feature[] features = cfg != null && cfg.writeNulls()
                ? new JSONWriter.Feature[]{JSONWriter.Feature.WriteNulls}
                : new JSONWriter.Feature[0];
        String json = JSON.toJSONString(obj, dateFormat, features);
        Snapshot.response(exchange.getStatusCode(), "application/json; charset=utf-8", obj);
        exchange.getResponseSender().send(json);
    }

    @Override public void jsonRaw(String json) {
        if (aborted || isResponseStarted()) return;
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        Snapshot.response(exchange.getStatusCode(), "application/json; charset=utf-8", json);
        exchange.getResponseSender().send(json);
    }

    @Override public void text(String text) {
        if (aborted || isResponseStarted()) return;
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain; charset=utf-8");
        Snapshot.response(exchange.getStatusCode(), "text/plain; charset=utf-8", text);
        exchange.getResponseSender().send(text);
    }

    @Override public void html(String html) {
        if (aborted || isResponseStarted()) return;
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=utf-8");
        Snapshot.response(exchange.getStatusCode(), "text/html; charset=utf-8", html);
        exchange.getResponseSender().send(html);
    }

    @Override public void raw(String body) {
        if (aborted || isResponseStarted()) return;
        Snapshot.response(exchange.getStatusCode(), null, body);
        exchange.getResponseSender().send(body);
    }

    @Override public void redirect(String url) {
        if (aborted || isResponseStarted()) return;
        if (url.indexOf('\r') >= 0 || url.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid redirect URL");
        }
        exchange.setStatusCode(302);
        exchange.getResponseHeaders().put(Headers.LOCATION, url);
        exchange.endExchange();
    }

    @Override public Context header(String name, String value) {
        if (aborted || isResponseStarted()) return this;
        exchange.getResponseHeaders().put(new HttpString(name), value);
        return this;
    }

    @Override public Context cookie(String name, String value, int maxAge) {
        if (aborted || isResponseStarted()) return this;
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
    public void sendFile(String filename, byte[] data, String contentType) {
        if (aborted || isResponseStarted()) return;
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
        String safeName = filename.replaceAll("[\\r\\n\"]", "");
        exchange.getResponseHeaders().put(new HttpString("Content-Disposition"),
                "attachment; filename=\"" + safeName + "\"");
        exchange.setResponseContentLength(data.length);
        Snapshot.response(exchange.getStatusCode(), contentType, Map.of("filename", filename, "bytes", data.length));
        exchange.getResponseSender().send(ByteBuffer.wrap(data));
    }

    @Override
    public void abort() { this.aborted = true; }

    @Override
    public boolean isAborted() { return aborted; }

    @Override
    public String ip() {
        String xff = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        var addr = exchange.getSourceAddress();
        String peer = addr != null ? addr.getAddress().getHostAddress() : null;
        String configured = app != null ? app.prop("aura.trusted-proxies") : null;
        return resolveClientIp(configured, peer, xff);
    }

    static String resolveClientIp(String configured, String peer, String forwardedFor) {
        String normalizedPeer = normalizeForwardedIp(peer);
        if (normalizedPeer == null) return peer;
        if (forwardedFor == null || forwardedFor.isBlank()
                || !isTrustedProxy(configured, normalizedPeer)) {
            return normalizedPeer;
        }

        String[] hops = forwardedFor.split(",", -1);
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = normalizeForwardedIp(hops[i]);
            if (hop == null) return normalizedPeer;
            if (!isTrustedProxy(configured, hop)) return hop;
        }
        return normalizedPeer;
    }

    static boolean isTrustedProxy(String configured, String peer) {
        String normalizedPeer = normalizeForwardedIp(peer);
        if (configured == null || configured.isBlank() || normalizedPeer == null) return false;
        for (String entry : configured.split(",")) {
            String rule = entry.trim();
            if (rule.isEmpty()) continue;
            String normalizedRule = normalizeForwardedIp(rule);
            if ((normalizedRule != null && normalizedRule.equals(normalizedPeer))
                    || cidrContains(rule, normalizedPeer)) {
                return true;
            }
        }
        return false;
    }

    static String normalizeForwardedIp(String value) {
        if (value == null) return null;
        String candidate = value.trim();
        if (candidate.isEmpty() || !candidate.matches("[0-9A-Fa-f:.]+")) return null;
        if (candidate.indexOf(':') < 0 && !isValidIpv4(candidate)) return null;
        try {
            return InetAddress.getByName(candidate).getHostAddress();
        } catch (UnknownHostException ignored) {
            return null;
        }
    }

    private static boolean isValidIpv4(String candidate) {
        String[] octets = candidate.split("\\.", -1);
        if (octets.length != 4) return false;
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) return false;
            int value = 0;
            for (int i = 0; i < octet.length(); i++) {
                char digit = octet.charAt(i);
                if (digit < '0' || digit > '9') return false;
                value = value * 10 + digit - '0';
            }
            if (value > 255) return false;
        }
        return true;
    }

    private static boolean cidrContains(String cidr, String address) {
        try {
            String[] parts = cidr.split("/", 2);
            if (parts.length != 2) return false;
            String normalizedNetwork = normalizeForwardedIp(parts[0]);
            if (normalizedNetwork == null) return false;
            byte[] network = InetAddress.getByName(normalizedNetwork).getAddress();
            byte[] candidate = InetAddress.getByName(address).getAddress();
            if (network.length != candidate.length) return false;
            int bits = Integer.parseInt(parts[1]);
            if (bits < 0 || bits > network.length * 8) return false;
            for (int i = 0; i < network.length; i++) {
                int remaining = bits - i * 8;
                int mask = remaining >= 8 ? 0xff : remaining <= 0 ? 0 : (0xff << (8 - remaining)) & 0xff;
                if ((network[i] & mask) != (candidate[i] & mask)) return false;
            }
            return true;
        } catch (UnknownHostException | NumberFormatException ignored) {
            return false;
        }
    }
}
