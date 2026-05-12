package io.aura.web;

import io.aura.annotation.*;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class ServiceRegistrar {

    private static final Set<String> CRUD_NAMES = Set.of("get", "list", "create", "update", "delete");

    static void register(Object service, Router router) {
        Class<?> clazz = service.getClass();
        Path pathAnn = clazz.getAnnotation(Path.class);
        String prefix = pathAnn != null ? pathAnn.value() : "";

        List<Method> annotated = new ArrayList<>();
        List<Method> convention = new ArrayList<>();

        for (Method m : clazz.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            if (m.getDeclaringClass() == Object.class) continue;

            if (hasRouteAnnotation(m)) {
                annotated.add(m);
            } else if (CRUD_NAMES.contains(m.getName())) {
                convention.add(m);
            }
        }

        // static paths first, then parameterized — prevents {id} from swallowing /search
        for (Method m : annotated) {
            registerMethod(m, prefix, service, router);
        }
        for (Method m : convention) {
            registerMethod(m, prefix, service, router);
        }
    }

    private static void registerMethod(Method m, String prefix, Object service, Router router) {
        RouteInfo info = resolveRoute(m, prefix);
        if (info == null) return;

        BaseRouteBuilder rb = switch (info.httpMethod) {
            case "GET" -> router.get(info.path, service, m.getName());
            case "POST" -> router.post(info.path, service, m.getName());
            case "PUT" -> router.put(info.path, service, m.getName());
            case "DELETE" -> router.delete(info.path, service, m.getName());
            default -> null;
        };
        if (rb == null) return;

        Desc methodDesc = m.getAnnotation(Desc.class);
        if (methodDesc != null) {
            rb.describe(methodDesc.value());
        }

        for (Parameter p : m.getParameters()) {
            Desc paramDesc = p.getAnnotation(Desc.class);
            if (paramDesc != null) {
                rb.param(p.getName(), paramDesc.value());
            }
        }
    }

    private static boolean hasRouteAnnotation(Method m) {
        return m.isAnnotationPresent(Get.class) || m.isAnnotationPresent(Post.class)
                || m.isAnnotationPresent(Put.class) || m.isAnnotationPresent(Delete.class);
    }

    private static RouteInfo resolveRoute(Method m, String prefix) {
        Get getAnn = m.getAnnotation(Get.class);
        if (getAnn != null) return new RouteInfo("GET", prefix + getAnn.value());

        Post postAnn = m.getAnnotation(Post.class);
        if (postAnn != null) return new RouteInfo("POST", prefix + postAnn.value());

        Put putAnn = m.getAnnotation(Put.class);
        if (putAnn != null) return new RouteInfo("PUT", prefix + putAnn.value());

        Delete delAnn = m.getAnnotation(Delete.class);
        if (delAnn != null) return new RouteInfo("DELETE", prefix + delAnn.value());

        String name = m.getName();
        return switch (name) {
            case "get" -> new RouteInfo("GET", prefix + "/" + pathParamSegment(m));
            case "list" -> new RouteInfo("GET", prefix);
            case "create" -> new RouteInfo("POST", prefix);
            case "update" -> new RouteInfo("PUT", prefix + "/" + pathParamSegment(m));
            case "delete" -> new RouteInfo("DELETE", prefix + "/" + pathParamSegment(m));
            default -> null;
        };
    }

    private static String pathParamSegment(Method m) {
        for (Parameter p : m.getParameters()) {
            Class<?> type = p.getType();
            if (type == int.class || type == long.class || type == String.class
                    || type == Integer.class || type == Long.class) {
                return "{" + p.getName() + "}";
            }
        }
        return "{id}";
    }

    private record RouteInfo(String httpMethod, String path) {}
}
