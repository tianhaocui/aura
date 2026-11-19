package io.aura.mcp;

import io.aura.contract.AccessPolicy;
import io.aura.contract.ApiParameter;
import io.aura.contract.CapabilityDefinition;
import io.aura.contract.ContractException;
import io.aura.contract.McpRouterSpec;
import io.aura.contract.Route;

import java.util.*;
import java.util.function.BiPredicate;

public class McpRouter implements McpRouterSpec {

    private final List<McpTool> tools = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String, McpResource> resources = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, McpPrompt> prompts = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, String> capabilityTools = Collections.synchronizedMap(new LinkedHashMap<>());

    /**
     * The only way to publish a tool: a shared {@link io.aura.contract.Route} contract whose
     * handler is an {@link McpHandler}.
     *
     * <p>Parameters are carried verbatim because re-declaring them is what used to strip the
     * source and the body POJO schema, collapsing a structured request into
     * {@code {"type":"string"}} — and because a second declaration is a second place the same
     * tool can be wrong. A tool with no HTTP endpoint is still a {@code Route}; it is simply a
     * {@code Route} nobody serves.
     *
     * <p>To expose a route that is already served over HTTP, rebind its handler with
     * {@link io.aura.contract.Route#withHandler(Object)}, which keeps every declared fact. A
     * route contract and an MCP handler passed side by side would leave the contract's own
     * handler silently unused, so this door takes neither a name nor a second handler.
     */
    public McpRouter tool(io.aura.contract.Route route) {
        Objects.requireNonNull(route, "route");
        if (!(route.handler() instanceof McpHandler handler)) {
            throw new ContractException("AURA_MCP_HANDLER_MISMATCH",
                    "MCP tool " + route.method() + " " + route.path() + " has a "
                            + route.handler().getClass().getName() + " handler; MCP invokes a Route"
                            + " whose handler is an McpHandler — use route.withHandler(mcpHandler)"
                            + " to project a route served in another transport.");
        }
        List<McpParam> params = params(route.params(), "tool " + route.method() + " " + route.path());
        register(new McpTool(McpUtil.buildToolName(route.method(), route.path()),
                route.description(), params, handler, route.permission(), route.authenticated()));
        return this;
    }

    /**
     * Projects an explicitly declared {@link CapabilityDefinition} into an MCP tool. The
     * definition stays authoritative: parameters pass through the same {@link McpParam} gate
     * and the same {@link McpUtil#inputSchema} funnel as a route tool, the access policy maps
     * through {@link AccessPolicy} onto the same authenticated/permission facts, and invocation
     * goes through the same fail-closed {@link #invoke} chain — a capability gets no second
     * execution path and no second schema.
     *
     * <p>The tool name is the published mapping {@link McpUtil#capabilityToolName}, never the
     * route-derived {@link McpUtil#buildToolName}: protocol naming must not become part of the
     * business identity. The mapping is queryable via {@link #capabilityBindings()} and each
     * projected tool advertises {@code x-aura-capability} in {@code tools/list}, so the
     * projection can be reconciled against the definition instead of trusted.
     */
    public McpRouter tool(CapabilityDefinition capability) {
        Objects.requireNonNull(capability, "capability");
        String toolName = McpUtil.capabilityToolName(capability.name());
        if (tools.stream().anyMatch(tool -> tool.name().equals(toolName))) {
            throw new ContractException("AURA_MCP_TOOL_DUPLICATE",
                    "MCP tool name '" + toolName + "' (capability " + capability.name()
                            + ") is already registered");
        }
        McpHandler handler = ctx -> capability.handler().handle(ctx.args(), ctx.userId());
        // A capability with no HTTP endpoint is still a Route — a Route nobody serves. The
        // contract carries the declared facts; this door only adds the protocol projection.
        Route route = Route.of("POST", "/capabilities/" + capability.name(), handler)
                .withParams(capability.params())
                .describedAs(capability.description());
        AccessPolicy access = capability.access();
        if (access instanceof AccessPolicy.Permission permission) {
            route = route.requireAuth(permission.id());
        } else if (access.authenticated()) {
            route = route.requireAuth();
        }
        register(new McpTool(toolName, route.description(),
                params(route.params(), "capability " + capability.name()), handler,
                route.permission(), route.authenticated()));
        capabilityTools.put(toolName, capability.name());
        return this;
    }

    /** The published tool-name → capability-name mapping of every capability projection. */
    public Map<String, String> capabilityBindings() {
        return Map.copyOf(capabilityTools);
    }

    /**
     * Reconciles every capability projection against the authoritative definitions. The
     * definition — not the manifest, not the projection — is the declaration side: expected
     * facts are recomputed through the same funnel the projection used
     * ({@link McpUtil#capabilityToolName}, {@link McpParam}, {@link McpUtil#inputSchema},
     * {@link AccessPolicy}), so a projection that lost or altered a fact is named, not trusted.
     *
     * <p>A capability that was never projected is not drift: projection is a per-capability
     * composition decision. Drift is a bound tool whose facts no longer match its definition,
     * a binding whose capability or tool is gone, or a schema entry claiming a capability
     * without a published binding.
     */
    public void reconcileCapabilities(io.aura.capability.CapabilityRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        List<String> drift = new ArrayList<>();
        Map<String, String> bindings = capabilityBindings();
        for (Map.Entry<String, String> binding : bindings.entrySet()) {
            String toolName = binding.getKey();
            String capabilityName = binding.getValue();
            McpTool tool = tools.stream().filter(t -> t.name().equals(toolName)).findFirst().orElse(null);
            if (tool == null) {
                drift.add("tool '" + toolName + "': bound to capability " + capabilityName + " but not registered");
                continue;
            }
            CapabilityDefinition definition;
            try {
                definition = registry.get(capabilityName);
            } catch (ContractException unknown) {
                drift.add("tool '" + toolName + "': capability " + capabilityName + " is not registered");
                continue;
            }
            if (!toolName.equals(McpUtil.capabilityToolName(definition.name()))) {
                drift.add("tool '" + toolName + "': name does not match the published mapping of " + definition.name());
            }
            Map<String, Object> expectedSchema;
            try {
                expectedSchema = McpUtil.inputSchema(definition.params().stream().map(McpParam::new).toList());
            } catch (ContractException invalid) {
                drift.add("tool '" + toolName + "': capability " + capabilityName
                        + " params cannot render: " + invalid.error().message());
                continue;
            }
            if (!expectedSchema.equals(McpUtil.inputSchema(tool.params()))) {
                drift.add("tool '" + toolName + "': inputSchema differs from capability " + capabilityName);
            }
            if (tool.authenticated() != definition.access().authenticated()) {
                drift.add("tool '" + toolName + "': authenticated flag differs from capability " + capabilityName);
            }
            if (!Objects.equals(tool.permission(), definition.access().permissionId())) {
                drift.add("tool '" + toolName + "': permission differs from capability " + capabilityName);
            }
            if (!Objects.equals(tool.description(), definition.description())) {
                drift.add("tool '" + toolName + "': description differs from capability " + capabilityName);
            }
        }
        for (Object entry : (List<?>) buildSchema().get("tools")) {
            Map<?, ?> view = (Map<?, ?>) entry;
            Object claimed = view.get("x-aura-capability");
            if (claimed == null) continue;
            String name = String.valueOf(view.get("name"));
            if (!claimed.equals(bindings.get(name))) {
                drift.add("tool '" + name + "': claims capability " + claimed + " without a published binding");
            }
        }
        if (!drift.isEmpty()) {
            throw new ContractException("AURA_MCP_PROJECTION_DRIFT", String.join("; ", drift));
        }
    }

    private static List<McpParam> params(List<ApiParameter> declared, String owner) {
        try {
            return declared.stream().map(McpParam::new).toList();
        } catch (ContractException e) {
            // McpParam owns the rule so no door can skip it; the door that knows which
            // capability or route broke it is the one that can say so.
            throw new ContractException(e.error().code(), e.error().message() + " (" + owner + ")");
        }
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
            String capability = capabilityTools.get(tool.name());
            if (capability != null) entry.put("x-aura-capability", capability);
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
        // The same fact the tool just advertised in tools/list, checked before it runs: an
        // argument marked required cannot be skipped and left for the handler to read as 0.
        for (McpParam param : tool.params()) {
            if (param.required() && (args == null || args.get(param.name()) == null)) {
                throw new McpMissingArgument(param.name());
            }
        }
        McpContext ctx = new McpContext(args, userId);
        return tool.handler().handle(ctx);
    }
}
