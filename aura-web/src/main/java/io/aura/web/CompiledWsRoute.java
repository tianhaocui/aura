package io.aura.web;

import io.aura.port.BaseHandler;
import io.aura.port.WsHandler;
import io.aura.contract.Route;
import io.undertow.server.HttpServerExchange;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.WebSocketProtocolHandshakeHandler;
import io.undertow.websockets.core.*;
import io.undertow.websockets.spi.WebSocketHttpExchange;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record CompiledWsRoute(Route contract, Pattern pattern, List<String> paramNames,
                       List<BaseHandler> beforeHandlers, WsHandler wsHandler,
                       long maxMessageBytes, WebSocketConnectionRegistry connections) {

    String rawPath() { return contract.path(); }

    Map<String, String> match(String rawPath) {
        String path = CompiledRoute.decodePath(rawPath);
        if (path == null) return null;
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
            if (!connections.register(channel)) {
                connections.reject(channel);
                return;
            }
            UndertowWsConnection conn = new UndertowWsConnection(
                    channel, pathParams, queryParams, () -> connections.touch(channel));
            conn.setErrorHandler(wsHandler.errorHandler());

            if (wsHandler.openHandler() != null) {
                try {
                    wsHandler.openHandler().accept(conn);
                } catch (Exception e) {
                    routeToError(conn, e);
                }
            }

            channel.getReceiveSetter().set(new AbstractReceiveListener() {
                @Override protected long getMaxTextBufferSize() { return maxMessageBytes; }
                @Override protected long getMaxBinaryBufferSize() { return maxMessageBytes; }

                @Override public void handleEvent(WebSocketChannel ch) {
                    connections.touch(ch);
                    super.handleEvent(ch);
                }

                @Override
                protected void onFullTextMessage(WebSocketChannel ch, BufferedTextMessage msg) {
                    if (wsHandler.messageHandler() != null) {
                        try {
                            wsHandler.messageHandler().accept(conn, msg.getData());
                        } catch (Exception e) {
                            routeToError(conn, e);
                        }
                    }
                }

                @Override
                protected void onCloseMessage(CloseMessage cm, WebSocketChannel ch) {
                    if (wsHandler.closeHandler() != null) {
                        try {
                            wsHandler.closeHandler().handle(conn, cm.getCode(), cm.getReason());
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
        if (wsHandler.errorHandler() != null) {
            try {
                wsHandler.errorHandler().handle(conn, error);
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

    static CompiledWsRoute compile(Route contract, List<BaseHandler> beforeHandlers,
                                   long maxMessageBytes, WebSocketConnectionRegistry connections) {
        String path = contract.path();
        List<String> paramNames = new ArrayList<>();
        // Only the braces-hashed placeholders are regex: escaping . and + by hand left every other
        // metacharacter live, so a path like /ws/a(b either matched paths the author never wrote
        // or failed to compile at all.
        Pattern paramPattern = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)}");
        Matcher m = paramPattern.matcher(path);
        StringBuilder regex = new StringBuilder();
        int last = 0;
        while (m.find()) {
            regex.append(Pattern.quote(path.substring(last, m.start())));
            regex.append("([^/]+)");
            paramNames.add(m.group(1));
            last = m.end();
        }
        regex.append(Pattern.quote(path.substring(last)));
        return new CompiledWsRoute(contract, Pattern.compile("^" + regex + "$"),
                paramNames, beforeHandlers, (WsHandler) contract.handler(),
                maxMessageBytes, connections);
    }
}
