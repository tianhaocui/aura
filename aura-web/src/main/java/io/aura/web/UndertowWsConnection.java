package io.aura.web;

import io.undertow.websockets.core.WebSocketCallback;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class UndertowWsConnection implements WsConnection {

    private static final Logger log = LoggerFactory.getLogger(UndertowWsConnection.class);

    private final WebSocketChannel channel;
    private final Map<String, String> pathParams;
    private final Map<String, String> queryParams;
    private final Map<String, Object> attrs = new ConcurrentHashMap<>();
    private volatile WsHandler.ErrorHandler errorHandler;

    UndertowWsConnection(WebSocketChannel channel, Map<String, String> pathParams,
                          Map<String, String> queryParams) {
        this.channel = channel;
        this.pathParams = pathParams;
        this.queryParams = queryParams;
    }

    void setErrorHandler(WsHandler.ErrorHandler handler) {
        this.errorHandler = handler;
    }

    @Override
    public boolean isOpen() { return channel.isOpen(); }

    @Override
    public void send(String message) {
        WebSockets.sendText(message, channel, new WebSocketCallback<Void>() {
            @Override
            public void complete(WebSocketChannel ch, Void context) {}

            @Override
            public void onError(WebSocketChannel ch, Void context, Throwable throwable) {
                log.debug("WebSocket send failed", throwable);
                if (errorHandler != null) {
                    errorHandler.handle(UndertowWsConnection.this, throwable);
                }
            }
        });
    }

    @Override
    public void close() {
        WebSockets.sendClose(1000, "", channel, null);
    }

    @Override
    public void close(int code, String reason) {
        WebSockets.sendClose(code, reason != null ? reason : "", channel, null);
    }

    @Override
    public String path(String name) { return pathParams.get(name); }

    @Override
    public String query(String name) { return queryParams.get(name); }

    @Override
    public void set(String key, Object value) { attrs.put(key, value); }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object val = attrs.get(key);
        if (val == null) return null;
        if (!type.isInstance(val)) {
            throw new ClassCastException("WsConnection attribute '" + key + "' is " +
                val.getClass().getName() + ", not " + type.getName());
        }
        return (T) val;
    }
}
