package io.aura;

import io.aura.web.WsHandler;

import java.util.function.Consumer;

/** An explicitly installed WebSocket endpoint owned by one Aura module. */
public record WebSocketContribution(String path, Consumer<WsHandler> configure) {
    public WebSocketContribution {
        if (path == null || path.isBlank() || path.charAt(0) != '/') {
            throw new IllegalArgumentException("WebSocket path must start with '/'");
        }
        if (configure == null) {
            throw new IllegalArgumentException("WebSocket handler configuration must not be null");
        }
    }
}
