package io.aura.mcp;

import java.lang.reflect.Method;
import java.util.*;

public class McpRouter {

    private final List<McpTool> tools = new ArrayList<>();
    private final Map<String, Map<String, Object>> toolMappings = new LinkedHashMap<>();

    public McpToolBuilder tool(String name, String description) {
        return new McpToolBuilder(this, name, description);
    }

    public McpRouter tool(String name, Object service, String method, String description) {
        Method m = resolveMethod(service, method);
        McpHandler handler = buildServiceHandler(service, m);
        List<McpParam> params = buildParamsFromMethod(m);
        tools.add(new McpTool(name, description, params, handler));
        return this;
    }

    public List<McpTool> tools() {
        return Collections.unmodifiableList(tools);
    }

    void register(McpTool tool) {
        tools.add(tool);
    }

    void registerMappings(String toolName, Map<String, Map<String, Object>> mappings) {
        toolMappings.put(toolName, new LinkedHashMap<>());
        mappings.forEach((paramName, map) -> toolMappings.get(toolName).putAll(map));
    }

    public Map<String, Object> buildSchema() {
        List<Map<String, Object>> toolList = new ArrayList<>();
        for (McpTool tool : tools) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("name", tool.name());
            if (tool.description() != null) entry.put("description", tool.description());
            entry.put("inputSchema", buildInputSchema(tool.params()));
            toolList.add(entry);
        }
        return Map.of("tools", toolList);
    }

    public Object invoke(String toolName, Map<String, Object> args) throws Exception {
        McpTool tool = tools.stream()
                .filter(t -> t.name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Tool not found: " + toolName));
        McpContext ctx = new McpContext(args, collectMappings(tool));
        return tool.handler().handle(ctx);
    }

    private Map<String, Map<String, Object>> collectMappings(McpTool tool) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (McpParam param : tool.params()) {
            if (param.isEnum()) {
                Map<String, Object> map = new LinkedHashMap<>();
                for (McpEnumValue ev : param.enumValues()) {
                    map.put(ev.label(), ev.code());
                }
                result.put(param.name(), map);
            }
        }
        return result;
    }

    private static Map<String, Object> buildInputSchema(List<McpParam> params) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (McpParam p : params) {
            var prop = new LinkedHashMap<String, Object>();
            prop.put("type", p.type());
            String desc = p.description();
            if (p.isEnum()) {
                List<String> enumLabels = p.enumValues().stream().map(McpEnumValue::label).toList();
                prop.put("enum", enumLabels);
                if (desc != null) {
                    desc = desc + ": " + String.join(", ", enumLabels);
                }
            }
            if (desc != null) prop.put("description", desc);
            properties.put(p.name(), prop);
            required.add(p.name());
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }

    private static Method resolveMethod(Object service, String methodName) {
        for (Method m : service.getClass().getDeclaredMethods()) {
            if (m.getName().equals(methodName) && java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                return m;
            }
        }
        throw new IllegalArgumentException("Method not found: " + methodName + " in " + service.getClass().getName());
    }

    private static McpHandler buildServiceHandler(Object service, Method method) {
        return ctx -> {
            var methodParams = method.getParameters();
            Object[] args = new Object[methodParams.length];
            for (int i = 0; i < methodParams.length; i++) {
                var p = methodParams[i];
                Object val = ctx.get(p.getName());
                args[i] = coerce(val, p.getType());
            }
            return method.invoke(service, args);
        };
    }

    private static List<McpParam> buildParamsFromMethod(Method method) {
        List<McpParam> params = new ArrayList<>();
        for (var p : method.getParameters()) {
            params.add(new McpParam(p.getName(), McpUtil.jsonType(p.getType().getSimpleName()), null, null));
        }
        return params;
    }

    private static Object coerce(Object val, Class<?> target) {
        if (val == null) return target.isPrimitive() ? 0 : null;
        String s = val.toString();
        if (target == int.class || target == Integer.class) return Integer.parseInt(s);
        if (target == long.class || target == Long.class) return Long.parseLong(s);
        if (target == boolean.class || target == Boolean.class) return Boolean.parseBoolean(s);
        if (target == double.class || target == Double.class) return Double.parseDouble(s);
        return s;
    }
}
