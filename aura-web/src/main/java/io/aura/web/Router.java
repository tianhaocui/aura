package io.aura.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Router {

    final List<RouteBuilder> routeBuilders = new ArrayList<>();
    final List<Handler> beforeHandlers = new ArrayList<>();
    final List<Handler> afterHandlers = new ArrayList<>();
    final List<Group> groups = new ArrayList<>();
    final Map<Class<? extends Exception>, ExceptionHandler<?>> exceptionHandlers = new LinkedHashMap<>();

    public RouteBuilder get(String path, Handler handler) {
        return addRoute("GET", path, handler);
    }

    public RouteBuilder get(String path, Object target, String method) {
        return addRoute("GET", path, new MethodRefHandler(target, method));
    }

    public RouteBuilder post(String path, Handler handler) {
        return addRoute("POST", path, handler);
    }

    public RouteBuilder post(String path, Object target, String method) {
        return addRoute("POST", path, new MethodRefHandler(target, method));
    }

    public RouteBuilder put(String path, Handler handler) {
        return addRoute("PUT", path, handler);
    }

    public RouteBuilder put(String path, Object target, String method) {
        return addRoute("PUT", path, new MethodRefHandler(target, method));
    }

    public RouteBuilder delete(String path, Handler handler) {
        return addRoute("DELETE", path, handler);
    }

    public RouteBuilder delete(String path, Object target, String method) {
        return addRoute("DELETE", path, new MethodRefHandler(target, method));
    }

    public Router before(Handler handler) {
        beforeHandlers.add(handler);
        return this;
    }

    public Router after(Handler handler) {
        afterHandlers.add(handler);
        return this;
    }

    public <T extends Exception> Router exception(Class<T> type, ExceptionHandler<T> handler) {
        exceptionHandlers.put(type, handler);
        return this;
    }

    public Router group(String prefix, java.util.function.Consumer<Router> block) {
        Router sub = new Router();
        block.accept(sub);
        groups.add(new Group(prefix, sub));
        return this;
    }

    public Router crud(String path, Object service) {
        var clazz = service.getClass();
        for (var m : clazz.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) continue;
            switch (m.getName()) {
                case "get" -> get(path + "/{" + firstParamName(m) + "}", service, "get");
                case "list" -> get(path, service, "list");
                case "create" -> post(path, service, "create");
                case "update" -> put(path + "/{" + firstParamName(m) + "}", service, "update");
                case "delete" -> delete(path + "/{" + firstParamName(m) + "}", service, "delete");
            }
        }
        return this;
    }

    private static String firstParamName(java.lang.reflect.Method m) {
        var params = m.getParameters();
        for (var p : params) {
            var type = p.getType();
            if (type == int.class || type == long.class || type == String.class
                    || type == Integer.class || type == Long.class) {
                return p.getName();
            }
        }
        return "id";
    }

    private RouteBuilder addRoute(String method, String path, Handler handler) {
        var rb = new RouteBuilder(new Route(method, path, handler));
        routeBuilders.add(rb);
        return rb;
    }

    record Route(String method, String path, Handler handler) {}
    record Group(String prefix, Router router) {}
}
