package io.aura.web;

import java.util.LinkedHashMap;
import java.util.Map;

public class BaseRouteBuilder {

    public final BaseRouter.Route route;
    public String description;
    public String returnType;
    public final Map<String, String> paramDescriptions = new LinkedHashMap<>();

    public BaseRouteBuilder(BaseRouter.Route route) {
        this.route = route;
    }

    public BaseRouteBuilder describe(String description) {
        this.description = description;
        return this;
    }

    public BaseRouteBuilder param(String name, String description) {
        paramDescriptions.put(name, description);
        return this;
    }
}
