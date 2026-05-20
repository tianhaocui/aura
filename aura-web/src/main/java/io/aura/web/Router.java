package io.aura.web;

public class Router extends BaseRouter {

    public BaseRouteBuilder get(String path, Object target, String method) {
        return addMethodRoute("GET", path, new MethodRefHandler(target, method));
    }

    public BaseRouteBuilder post(String path, Object target, String method) {
        return addMethodRoute("POST", path, new MethodRefHandler(target, method));
    }

    public BaseRouteBuilder put(String path, Object target, String method) {
        return addMethodRoute("PUT", path, new MethodRefHandler(target, method));
    }

    public BaseRouteBuilder delete(String path, Object target, String method) {
        return addMethodRoute("DELETE", path, new MethodRefHandler(target, method));
    }

    public BaseRouter crud(String path, Object service) {
        return crud(path, service, "get", "list", "create", "update", "delete");
    }

    public BaseRouter crud(String path, Object service, String... methods) {
        var allowed = java.util.Set.of("get", "list", "create", "update", "delete");
        for (String m : methods) {
            if (!allowed.contains(m)) {
                throw new IllegalArgumentException("Unknown crud method: '" + m + "'. Allowed: " + allowed);
            }
        }
        var selected = java.util.Set.of(methods);
        var clazz = service.getClass();
        for (var m : clazz.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) continue;
            if (!selected.contains(m.getName())) continue;
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

    private BaseRouteBuilder addMethodRoute(String method, String path, MethodRefHandler handler) {
        BaseRouteBuilder rb = addRoute(method, path, handler);
        var m = handler.resolvedMethod();
        if (m.getReturnType() != void.class) {
            rb.returnType = m.getReturnType().getSimpleName();
        }
        return rb;
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
}
