package io.aura.web;

import java.util.LinkedHashMap;
import java.util.Map;

public class RouteBuilder {

    final Router.Route route;
    String description;
    String returnType;
    final Map<String, String> paramDescriptions = new LinkedHashMap<>();

    RouteBuilder(Router.Route route) {
        this.route = route;
        if (route.handler() instanceof MethodRefHandler mh) {
            var m = mh.resolvedMethod();
            if (m.getReturnType() != void.class) {
                this.returnType = m.getReturnType().getSimpleName();
            }
        }
    }

    public RouteBuilder describe(String description) {
        this.description = description;
        return this;
    }

    public RouteBuilder param(String name, String description) {
        paramDescriptions.put(name, description);
        return this;
    }
}
