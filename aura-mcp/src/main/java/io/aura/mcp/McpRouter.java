package io.aura.mcp;

import io.aura.contract.McpRouterSpec;

import java.util.*;
import java.util.function.BiPredicate;

public class McpRouter implements McpRouterSpec {

    private final List<McpTool> tools = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String, McpResource> resources = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, McpPrompt> prompts = Collections.synchronizedMap(new LinkedHashMap<>());

    public McpToolBuilder tool(String name, String description) {
        return new McpToolBuilder(this, name, description);
    }

    /**
     * Exposes a shared HTTP route contract as a deterministic MCP tool. Parameters are
     * carried verbatim: re-declaring them here is what used to strip the source and the
     * body POJO schema, collapsing a structured request into {@code {"type":"string"}}.
     */
    public McpRouter tool(io.aura.contract.Route route, McpHandler handler) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(handler, "handler");
        List<McpParam> params = route.params().stream()
                .map(parameter -> new McpParam(parameter, List.of()))
                .toList();
        register(new McpTool(McpUtil.buildToolName(route.method(), route.path()),
                route.description(), params, handler, route.permission(), route.authenticated()));
        return this;
    }

    public List<McpTool> tools() {
        return Collections.unmodifiableList(tools);
    }

    public McpRouter resource(String uri, String name, String description, String mimeType, String text) {
        resources.put(uri, new McpResource(uri, name, description, mimeType, text));
        return this;
    }

    public McpRouter prompt(String name, String description, String template, String... arguments) {
        prompts.put(name, new McpPrompt(name, description, template,
                arguments == null ? List.of() : List.of(arguments)));
        return this;
    }

    public Map<String, Object> listResources() {
        List<Map<String, Object>> entries = new ArrayList<>();
        synchronized (resources) {
        for (McpResource resource : resources.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("uri", resource.uri());
            entry.put("name", resource.name());
            if (resource.description() != null) entry.put("description", resource.description());
            if (resource.mimeType() != null) entry.put("mimeType", resource.mimeType());
            entries.add(entry);
        }
        }
        return Map.of("resources", entries);
    }

    public Map<String, Object> readResource(String uri) {
        McpResource resource = resources.get(uri);
        if (resource == null) return Map.of("contents", List.of());
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("uri", resource.uri());
        if (resource.mimeType() != null) content.put("mimeType", resource.mimeType());
        content.put("text", resource.text());
        return Map.of("contents", List.of(content));
    }

    public Map<String, Object> listPrompts() {
        List<Map<String, Object>> entries = new ArrayList<>();
        synchronized (prompts) {
        for (McpPrompt prompt : prompts.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", prompt.name());
            if (prompt.description() != null) entry.put("description", prompt.description());
            if (!prompt.arguments().isEmpty()) {
                entry.put("arguments", prompt.arguments().stream()
                        .map(name -> Map.<String, Object>of("name", name, "required", true))
                        .toList());
            }
            entries.add(entry);
        }
        }
        return Map.of("prompts", entries);
    }

    public Map<String, Object> getPrompt(String name, Map<String, Object> arguments) {
        McpPrompt prompt = prompts.get(name);
        if (prompt == null) return Map.of("messages", List.of());
        String text = prompt.template();
        for (String argument : prompt.arguments()) {
            Object value = arguments == null ? null : arguments.get(argument);
            text = text.replace("{" + argument + "}", value == null ? "" : value.toString());
        }
        return Map.of("messages", List.of(Map.of(
                "role", "user",
                "content", Map.of("type", "text", "text", text))));
    }

    void register(McpTool tool) {
        tools.add(tool);
    }

    public Map<String, Object> buildSchema() {
        List<Map<String, Object>> toolList = new ArrayList<>();
        for (McpTool tool : tools) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("name", tool.name());
            if (tool.description() != null) entry.put("description", tool.description());
            if (tool.authenticated()) entry.put("x-aura-authenticated", true);
            if (tool.permission() != null) entry.put("x-aura-permission", tool.permission());
            entry.put("inputSchema", McpUtil.inputSchema(tool.params()));
            toolList.add(entry);
        }
        return Map.of("tools", toolList);
    }

    public Object invoke(String toolName, Map<String, Object> args) throws Exception {
        return invoke(toolName, args, null, null);
    }

    /** Invokes a tool with an explicit authenticated identity and permission checker. */
    public Object invoke(String toolName, Map<String, Object> args, String userId,
                         BiPredicate<String, String> authorizer) throws Exception {
        McpTool tool = tools.stream()
                .filter(t -> t.name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Tool not found: " + toolName));
        if (tool.authenticated() && (userId == null || userId.isBlank())) {
            throw new SecurityException("MCP authentication required for tool " + toolName);
        }
        if (tool.permission() != null) {
            if (authorizer == null) {
                throw new SecurityException("MCP authorization is not configured for tool " + toolName);
            }
            if (!authorizer.test(userId, tool.permission())) {
                throw new SecurityException("MCP permission denied for tool " + toolName);
            }
        }
        McpContext ctx = new McpContext(args, collectMappings(tool), userId);
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
}
