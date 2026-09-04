package io.aura.web;

import io.aura.contract.ApiParameter;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public class BaseRouteBuilder {

    public final BaseRouter.Route route;
    public String description;
    public String returnType;
    public final Map<String, String> paramDescriptions = new LinkedHashMap<>();
    private final List<ApiParameter> parameters = new ArrayList<>();

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

    /** Declares an AI-facing parameter for lambda routes without relying on reflection. */
    public BaseRouteBuilder param(String name, String type, String source,
                                  boolean required, String description) {
        parameters.add(new ApiParameter(name, type, source, required, description));
        return this;
    }

    public List<ApiParameter> parameters() {
        return List.copyOf(parameters);
    }
}
