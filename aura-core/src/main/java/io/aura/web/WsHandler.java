package io.aura.web;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class WsHandler {

    // package-private: accessed by CompiledWsRoute in aura-web
    Consumer<WsConnection> onOpen;
    BiConsumer<WsConnection, String> onMessage;
    CloseHandler onClose;
    ErrorHandler onError;

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

    @FunctionalInterface
    public interface CloseHandler {
        void handle(WsConnection conn, int code, String reason);
    }

    @FunctionalInterface
    public interface ErrorHandler {
        void handle(WsConnection conn, Throwable error);
    }
}
