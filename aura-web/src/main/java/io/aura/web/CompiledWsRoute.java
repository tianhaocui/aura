package io.aura.web;

import io.undertow.server.HttpServerExchange;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.WebSocketProtocolHandshakeHandler;
import io.undertow.websockets.core.*;
import io.undertow.websockets.spi.WebSocketHttpExchange;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record CompiledWsRoute(String rawPath, Pattern pattern, List<String> paramNames,
                       List<BaseHandler> beforeHandlers, WsHandler wsHandler) {

    Map<String, String> match(String path) {
        Matcher m = pattern.matcher(path);
        if (!m.matches()) return null;
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < paramNames.size(); i++) {
            params.put(paramNames.get(i), m.group(i + 1));
        }
        return params;
    }

    void upgrade(HttpServerExchange exchange, Map<String, String> pathParams) throws Exception {
        Map<String, String> queryParams = parseQuery(exchange);

        WebSocketConnectionCallback callback = (WebSocketHttpExchange wsExchange, WebSocketChannel channel) -> {
            UndertowWsConnection conn = new UndertowWsConnection(channel, pathParams, queryParams);
            conn.setErrorHandler(wsHandler.onError);

            if (wsHandler.onOpen != null) {
                try {
                    wsHandler.onOpen.accept(conn);
                } catch (Exception e) {
                    routeToError(conn, e);
                }
            }

            channel.getReceiveSetter().set(new AbstractReceiveListener() {
                @Override
                protected void onFullTextMessage(WebSocketChannel ch, BufferedTextMessage msg) {
                    if (wsHandler.onMessage != null) {
                        try {
                            wsHandler.onMessage.accept(conn, msg.getData());
                        } catch (Exception e) {
                            routeToError(conn, e);
                        }
                    }
                }

                @Override
                protected void onCloseMessage(CloseMessage cm, WebSocketChannel ch) {
                    if (wsHandler.onClose != null) {
                        try {
                            wsHandler.onClose.handle(conn, cm.getCode(), cm.getReason());
                        } catch (Exception e) {
                            routeToError(conn, e);
                        }
                    }
                }

                @Override
                protected void onError(WebSocketChannel ch, Throwable error) {
                    routeToError(conn, error);
                }
            });
            channel.resumeReceives();
        };

        new WebSocketProtocolHandshakeHandler(callback).handleRequest(exchange);
    }

    private void routeToError(UndertowWsConnection conn, Throwable error) {
        if (wsHandler.onError != null) {
            try {
                wsHandler.onError.handle(conn, error);
            } catch (Exception suppressed) {
                error.addSuppressed(suppressed);
            }
        }
    }


    private static Map<String, String> parseQuery(HttpServerExchange exchange) {
        Map<String, String> params = new HashMap<>();
        exchange.getQueryParameters().forEach((k, v) -> {
            if (!v.isEmpty()) params.put(k, v.getFirst());
        });
        return params;
    }

    static CompiledWsRoute compile(String path, List<BaseHandler> beforeHandlers, WsHandler handler) {
        List<String> paramNames = new ArrayList<>();
        String regex = path
                .replace(".", "\\.")
                .replace("+", "\\+")
                .replaceAll("\\{([a-zA-Z_][a-zA-Z0-9_]*)}", "([^/]+)");
        Pattern paramPattern = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)}");
        Matcher m = paramPattern.matcher(path);
        while (m.find()) {
            paramNames.add(m.group(1));
        }
        return new CompiledWsRoute(path, Pattern.compile("^" + regex + "$"),
                paramNames, beforeHandlers, handler);
    }
}
