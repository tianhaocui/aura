package io.aura.port;

import io.aura.contract.Route;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class BaseRouter {

    public final List<BaseRouteBuilder> routeBuilders = new ArrayList<>();
    public final List<BaseHandler> beforeHandlers = new ArrayList<>();
    public final List<BaseHandler> afterHandlers = new ArrayList<>();
    public final List<Group> groups = new ArrayList<>();
    public final List<WsRoute> wsRoutes = new ArrayList<>();

    public BaseRouteBuilder get(String path, BaseHandler handler) {
        return addRoute("GET", path, handler);
    }

    public BaseRouteBuilder post(String path, BaseHandler handler) {
        return addRoute("POST", path, handler);
    }

    public BaseRouteBuilder put(String path, BaseHandler handler) {
        return addRoute("PUT", path, handler);
    }

    public BaseRouteBuilder delete(String path, BaseHandler handler) {
        return addRoute("DELETE", path, handler);
    }

    public BaseRouteBuilder patch(String path, BaseHandler handler) {
        return addRoute("PATCH", path, handler);
    }

    public BaseRouteBuilder head(String path, BaseHandler handler) {
        return addRoute("HEAD", path, handler);
    }

    public BaseRouteBuilder options(String path, BaseHandler handler) {
        return addRoute("OPTIONS", path, handler);
    }

    public BaseRouter before(BaseHandler handler) {
        beforeHandlers.add(handler);
        return this;
    }

    public BaseRouter after(BaseHandler handler) {
        afterHandlers.add(handler);
        return this;
    }

    public BaseRouter group(String prefix, Consumer<BaseRouter> block) {
        BaseRouter sub = new BaseRouter();
        block.accept(sub);
        groups.add(new Group(prefix, sub));
        return this;
    }

    public BaseRouter ws(String path, Consumer<WsHandler> config) {
        WsHandler handler = new WsHandler();
        config.accept(handler);
        return ws(Route.webSocket(path, handler));
    }

    /** Declares a WebSocket endpoint from its route contract, the same shape HTTP uses. */
    public BaseRouter ws(Route route) {
        if (!route.isWebSocket()) {
            throw new IllegalArgumentException("WebSocket route must use method WS: " + route.path());
        }
        if (!(route.handler() instanceof WsHandler)) {
            // Reaching the handshake with anything else fails there, far from the mistake.
            throw new IllegalArgumentException("WebSocket route handler must be a WsHandler: " + route.path());
        }
        wsRoutes.add(new WsRoute(route));
        return this;
    }

    public BaseRouteBuilder addRoute(String method, String path, BaseHandler handler) {
        var rb = new BaseRouteBuilder(Route.of(method, path, handler));
        routeBuilders.add(rb);
        return rb;
    }

    public record Group(String prefix, BaseRouter router) {}

    /** A WebSocket endpoint: the contract, and nothing else. */
    public record WsRoute(Route route) {
        public String path() { return route.path(); }
        public WsHandler handler() { return (WsHandler) route.handler(); }
    }
}
