package io.aura.port;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class WsHandler {

    // The adapter reads these through accessors. They used to be package-private and reachable
    // only because aura-core and aura-web both declared io.aura.web; a port's state has to be
    // readable across a real package boundary or the boundary is not there.
    private Consumer<WsConnection> onOpen;
    private BiConsumer<WsConnection, String> onMessage;
    private CloseHandler onClose;
    private ErrorHandler onError;

    public WsHandler onOpen(Consumer<WsConnection> handler) {
        this.onOpen = handler;
        return this;
    }

    public WsHandler onMessage(BiConsumer<WsConnection, String> handler) {
        this.onMessage = handler;
        return this;
    }

    public WsHandler onClose(CloseHandler handler) {
        this.onClose = handler;
        return this;
    }

    public WsHandler onError(ErrorHandler handler) {
        this.onError = handler;
        return this;
    }

    public Consumer<WsConnection> openHandler() { return onOpen; }
    public BiConsumer<WsConnection, String> messageHandler() { return onMessage; }
    public CloseHandler closeHandler() { return onClose; }
    public ErrorHandler errorHandler() { return onError; }

    @FunctionalInterface
    public interface CloseHandler {
        void handle(WsConnection conn, int code, String reason);
    }

    @FunctionalInterface
    public interface ErrorHandler {
        void handle(WsConnection conn, Throwable error);
    }
}
