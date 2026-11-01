package io.aura.web;

import io.aura.contract.ApiParameter;
import io.aura.contract.Route;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BaseRouteBuilder {

    /** The one route contract; every declaration below is a typed field of it. */
    public Route route;
    /** Name-to-description overlay for reflection-derived parameters. */
    public final Map<String, String> paramDescriptions = new LinkedHashMap<>();

    public BaseRouteBuilder(Route route) {
        this.route = route;
    }

    public BaseRouteBuilder describe(String description) {
        route = route.describedAs(description);
        return this;
    }

    public BaseRouteBuilder streaming() {
        route = route.withStreaming(true);
        return this;
    }

    public BaseRouteBuilder param(String name, String description) {
        paramDescriptions.put(name, description);
        return this;
    }

    /** Declares an AI-facing parameter for lambda routes without relying on reflection. */
    public BaseRouteBuilder param(String name, String type, String source,
                                  boolean required, String description) {
        return param(new ApiParameter(name, type, source, required, description));
    }

    /** Preserves a generated structured schema when assembling a route. */
    public BaseRouteBuilder param(ApiParameter parameter) {
        java.util.Objects.requireNonNull(parameter, "parameter");
        List<ApiParameter> parameters = new ArrayList<>(route.params());
        parameters.add(parameter);
        route = route.withParams(parameters);
        return this;
    }

    public List<ApiParameter> parameters() {
        return route.params();
    }
}
