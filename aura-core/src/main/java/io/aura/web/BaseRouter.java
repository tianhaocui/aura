package io.aura.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class BaseRouter {

    public final List<BaseRouteBuilder> routeBuilders = new ArrayList<>();
    public final List<BaseHandler> beforeHandlers = new ArrayList<>();
    public final List<BaseHandler> afterHandlers = new ArrayList<>();
    public final List<Group> groups = new ArrayList<>();
    public final List<WsRoute> wsRoutes = new ArrayList<>();
    public final Map<Class<? extends Exception>, BaseExceptionHandler<?>> exceptionHandlers = new LinkedHashMap<>();

    public BaseRouteBuilder get(String path, BaseHandler handler) {
        return addRoute("GET", path, handler);
    }

    public BaseRouteBuilder get(String path, Object target, String method) {
        throw new UnsupportedOperationException("Service method routing requires aura-web");
    }

    public BaseRouteBuilder post(String path, BaseHandler handler) {
        return addRoute("POST", path, handler);
    }

    public BaseRouteBuilder post(String path, Object target, String method) {
        throw new UnsupportedOperationException("Service method routing requires aura-web");
    }

    public BaseRouteBuilder put(String path, BaseHandler handler) {
        return addRoute("PUT", path, handler);
    }

    public BaseRouteBuilder put(String path, Object target, String method) {
        throw new UnsupportedOperationException("Service method routing requires aura-web");
    }

    public BaseRouteBuilder delete(String path, BaseHandler handler) {
        return addRoute("DELETE", path, handler);
    }

    public BaseRouteBuilder delete(String path, Object target, String method) {
        throw new UnsupportedOperationException("Service method routing requires aura-web");
    }

    public BaseRouter crud(String path, Object service) {
        throw new UnsupportedOperationException("crud() requires aura-web");
    }

    public BaseRouter crud(String path, Object service, String... methods) {
        throw new UnsupportedOperationException("crud() requires aura-web");
    }

    public BaseRouter before(BaseHandler handler) {
        beforeHandlers.add(handler);
        return this;
    }

    public BaseRouter after(BaseHandler handler) {
        afterHandlers.add(handler);
        return this;
    }

    public <T extends Exception> BaseRouter exception(Class<T> type, BaseExceptionHandler<T> handler) {
        exceptionHandlers.put(type, handler);
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
        wsRoutes.add(new WsRoute(path, handler));
        return this;
    }

    public BaseRouteBuilder addRoute(String method, String path, BaseHandler handler) {
        var rb = new BaseRouteBuilder(new Route(method, path, handler));
        routeBuilders.add(rb);
        return rb;
    }

    public record Route(String method, String path, BaseHandler handler) {}
    public record Group(String prefix, BaseRouter router) {}
    public record WsRoute(String path, WsHandler handler) {}
}
