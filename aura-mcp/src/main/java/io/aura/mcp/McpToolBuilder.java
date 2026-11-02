package io.aura.mcp;

import io.aura.contract.ApiParameter;
import io.aura.contract.ScalarType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Declares a hand-authored MCP tool. It is a shorter way to write the same
 * {@link ApiParameter} contract a {@link McpRouter#tool(io.aura.contract.Route, McpHandler)}
 * gets from an HTTP route, not a second contract: the builder names Java types and leaves every
 * Java-to-JSON decision to {@link McpUtil#inputSchema}, and it accepts only the types a tool
 * argument can actually carry.
 */
public class McpToolBuilder {

    private final String name;
    private final String description;
    private final List<McpParam> params = new ArrayList<>();
    private McpHandler handler;
    private String permission;
    private final McpRouter router;

    McpToolBuilder(McpRouter router, String name, String description) {
        this.router = router;
        this.name = name;
        this.description = description;
    }

    public McpToolBuilder param(String name, Class<?> type, String description) {
        if (type.isEnum()) {
            return addEnumParam(name, type, description);
        }
        params.add(declared(name, type, description, null, true));
        return this;
    }

    public McpToolBuilder optional(String name, Class<?> type, String description) {
        if (type.isEnum()) {
            return addEnumParam(name, type, description, false);
        }
        params.add(declared(name, type, description, null, false));
        return this;
    }

    public McpToolBuilder param(String name, Class<?> type, String description, Map<String, ?> mapping) {
        List<McpEnumValue> enumValues = new ArrayList<>();
        for (var entry : mapping.entrySet()) {
            enumValues.add(new McpEnumValue(entry.getKey(), entry.getValue()));
        }
        params.add(declared(name, type, description, enumValues, true));
        return this;
    }

    /**
     * A tool argument arrives as a JSON scalar, so a complex type cannot be declared here.
     * Refusing it at registration beats publishing {@code "type":"string"} for an object the
     * handler will never receive; a real body belongs on an HTTP route.
     */
    private static McpParam declared(String name, Class<?> type, String description,
                                     List<McpEnumValue> enumValues, boolean required) {
        if (!ScalarType.isScalar(type.getName()) && !type.isEnum()) {
            throw new IllegalArgumentException("Tool argument '" + name + "' cannot be a "
                    + type.getName() + "; a hand-authored tool takes scalar arguments. Expose the"
                    + " HTTP route with McpRouter.tool(Route, McpHandler) so a body is described"
                    + " by the route contract.");
        }
        return new McpParam(new ApiParameter(name, type.getSimpleName(), "tool",
                required, description), enumValues);
    }

    public McpToolBuilder handler(McpHandler handler) {
        this.handler = handler;
        router.register(build());
        return this;
    }

    /** Requires the caller to hold this permission before the tool can run. */
    public McpToolBuilder permission(String permission) {
        if (permission == null || permission.isBlank()) {
            throw new IllegalArgumentException("permission must not be blank");
        }
        this.permission = permission.trim();
        return this;
    }

    McpTool build() {
        return new McpTool(name, description, List.copyOf(params), handler, permission);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private McpToolBuilder addEnumParam(String name, Class<?> enumType, String description) {
        return addEnumParam(name, enumType, description, true);
    }

    private McpToolBuilder addEnumParam(String name, Class<?> enumType, String description,
                                        boolean required) {
        Object[] constants = enumType.getEnumConstants();
        List<McpEnumValue> enumValues = new ArrayList<>();
        for (Object c : constants) {
            String enumName = ((Enum<?>) c).name();
            String label = extractLabel(c);
            enumValues.add(new McpEnumValue(label != null ? enumName + "(" + label + ")" : enumName, enumName));
        }
        params.add(declared(name, enumType, description, enumValues, required));
        return this;
    }

    private static String extractLabel(Object enumConstant) {
        // prefer label() method (interface or duck-typed)
        try {
            var method = enumConstant.getClass().getMethod("label");
            return (String) method.invoke(enumConstant);
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            return null;
        }
        // fallback to label field
        try {
            var field = enumConstant.getClass().getDeclaredField("label");
            field.setAccessible(true);
            return (String) field.get(enumConstant);
        } catch (Exception e) {
            return null;
        }
    }
}
