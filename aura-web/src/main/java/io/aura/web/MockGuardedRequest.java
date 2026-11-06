package io.aura.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory {@link GuardedRequest}, so {@code TestClient} reaches a handler only after the same
 * host, admission, CSRF, and CORS decisions a real socket must survive.
 */
final class MockGuardedRequest implements GuardedRequest {

    private static final String DEFAULT_HOST = "localhost";

    private final String method;
    private final String requestTarget;
    private final Map<String, String> requestHeaders;
    private final boolean secure;
    private final String peerIp;
    private final Map<String, String> responseHeaders = new LinkedHashMap<>();
    private final List<Runnable> completions = new ArrayList<>();

    MockGuardedRequest(String method, String requestTarget, Map<String, String> requestHeaders,
                       boolean secure, String peerIp) {
        this.method = method;
        this.requestTarget = requestTarget;
        this.requestHeaders = requestHeaders;
        this.secure = secure;
        this.peerIp = peerIp;
    }

    Map<String, String> responseHeaders() { return responseHeaders; }

    /** Runs each completion action once, the way Undertow ends an exchange. */
    void complete() {
        List<Runnable> pending = new ArrayList<>(completions);
        completions.clear();
        for (Runnable action : pending) action.run();
    }

    @Override public String method() { return method; }

    @Override public String path() { return requestTarget; }

    @Override
    public String header(String name) {
        for (Map.Entry<String, String> entry : requestHeaders.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }

    @Override
    public String cookie(String name) {
        String cookieHeader = header("Cookie");
        if (cookieHeader == null) return null;
        for (String pair : cookieHeader.split(";")) {
            int separator = pair.indexOf('=');
            if (separator < 0) continue;
            if (pair.substring(0, separator).trim().equals(name)) return pair.substring(separator + 1).trim();
        }
        return null;
    }

    @Override public boolean secure() { return secure; }

    @Override
    public String hostName() {
        String host = hostAndPort();
        if (host.startsWith("[")) {
            int closing = host.indexOf(']');
            return closing > 0 ? host.substring(1, closing) : host;
        }
        int port = host.lastIndexOf(':');
        return port > 0 ? host.substring(0, port) : host;
    }

    @Override
    public String hostAndPort() {
        String host = header("Host");
        return host == null || host.isBlank() ? DEFAULT_HOST : host.trim();
    }

    @Override public String scheme() { return secure ? "https" : "http"; }

    @Override public String peerIp() { return peerIp; }

    @Override
    public void responseHeader(String name, String value) {
        responseHeaders.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
        responseHeaders.put(name, value);
    }

    @Override
    public void addResponseHeader(String name, String value) {
        String existing = null;
        for (Map.Entry<String, String> entry : responseHeaders.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) { existing = entry.getValue(); break; }
        }
        if (existing == null) responseHeaders.put(name, value);
        else if (!existing.contains(value)) responseHeaders.put(name, existing + ", " + value);
    }

    @Override
    public void onComplete(Runnable action) {
        completions.add(action);
    }
}
