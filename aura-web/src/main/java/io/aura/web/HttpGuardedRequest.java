package io.aura.web;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HttpString;

/** Undertow-backed {@link GuardedRequest}, so the guard chain runs on real sockets. */
final class HttpGuardedRequest implements GuardedRequest {

    private final HttpServerExchange exchange;
    private final String method;

    HttpGuardedRequest(HttpServerExchange exchange) {
        this.exchange = exchange;
        this.method = exchange.getRequestMethod().toString();
    }

    @Override public String method() { return method; }

    @Override public String path() { return exchange.getRequestURI(); }

    @Override public String header(String name) { return exchange.getRequestHeaders().getFirst(name); }

    @Override
    public String cookie(String name) {
        var cookie = exchange.getRequestCookie(name);
        return cookie == null ? null : cookie.getValue();
    }

    @Override public boolean secure() { return exchange.isSecure(); }

    @Override public String hostName() { return exchange.getHostName(); }

    @Override public String hostAndPort() { return exchange.getHostAndPort(); }

    @Override public String scheme() { return exchange.getRequestScheme(); }

    @Override
    public String peerIp() {
        return exchange.getSourceAddress() == null || exchange.getSourceAddress().getAddress() == null
                ? null : exchange.getSourceAddress().getAddress().getHostAddress();
    }

    @Override
    public void responseHeader(String name, String value) {
        exchange.getResponseHeaders().put(new HttpString(name), value);
    }

    @Override
    public void addResponseHeader(String name, String value) {
        exchange.getResponseHeaders().add(new HttpString(name), value);
    }

    @Override
    public void onComplete(Runnable action) {
        exchange.addExchangeCompleteListener((completed, next) -> {
            action.run();
            next.proceed();
        });
    }
}
