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
    public final Map<Class<? extends Exception>, BaseExceptionHandler<?>> exceptionHandlers = new LinkedHashMap<>();

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

    public BaseRouteBuilder addRoute(String method, String path, BaseHandler handler) {
        var rb = new BaseRouteBuilder(new Route(method, path, handler));
        routeBuilders.add(rb);
        return rb;
    }

    public record Route(String method, String path, BaseHandler handler) {}
    public record Group(String prefix, BaseRouter router) {}
}
