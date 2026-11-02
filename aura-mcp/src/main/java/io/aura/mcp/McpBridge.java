package io.aura.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.aura.security.IpSafety;

import java.io.*;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

public class McpBridge {

    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    /** Seam that lets tests script DNS answers to prove the DNS-rebinding defence. */
    @FunctionalInterface
    interface DnsResolver {
        List<InetAddress> resolve(String host) throws java.net.UnknownHostException;
    }

    private static final DnsResolver SYSTEM_DNS =
            host -> List.of(InetAddress.getAllByName(host));

    private final String baseUrl;
    private final URI baseUri;
    private final PrintStream out;
    private final String schemaToken;
    /**
     * True when every outbound connection must terminate on a publicly routable address.
     * The constructor performs the first fail-fast validation; {@link #requirePublicAtConnect}
     * re-validates immediately before each request so a short-TTL rebinding answer that
     * appears after construction is still caught at connection time (see method javadoc
     * for the residual window).
     */
    private final boolean enforcePublic;
    private final DnsResolver dnsResolver;
    private volatile HttpClient httpClient;
    private JSONArray routes;

    public McpBridge(String baseUrl) {
        this(baseUrl, System.out, configuredSchemaToken());
    }

    public McpBridge(String baseUrl, PrintStream out) {
        this(baseUrl, out, configuredSchemaToken());
    }

    public McpBridge(String baseUrl, PrintStream out, String schemaToken) {
        this(baseUrl, out, schemaToken, allowPrivateUrls());
    }

    /** Creates a bridge with an explicit private/local target policy. */
    public McpBridge(String baseUrl, PrintStream out, String schemaToken, boolean allowPrivateUrl) {
        this(baseUrl, out, schemaToken, allowPrivateUrl, SYSTEM_DNS);
    }

    McpBridge(String baseUrl, PrintStream out, String schemaToken, boolean allowPrivateUrl,
              DnsResolver dnsResolver) {
        URI parsed = URI.create(Objects.requireNonNull(baseUrl, "baseUrl"));
        if (!("http".equalsIgnoreCase(parsed.getScheme()) || "https".equalsIgnoreCase(parsed.getScheme()))
                || parsed.getHost() == null || parsed.getUserInfo() != null
                || parsed.getRawFragment() != null || parsed.getRawQuery() != null) {
            throw new IllegalArgumentException(
                    "baseUrl must be an absolute HTTP(S) URL without userinfo, query, or fragment");
        }
        this.enforcePublic = !allowPrivateUrl;
        this.dnsResolver = dnsResolver;
        if (enforcePublic) {
            // Fail-fast first validation; the binding enforcement happens per request in
            // requirePublicAtConnect, close enough to the socket call to defeat rebinding.
            try {
                IpSafety.pinToPublic(dnsResolver.resolve(parsed.getHost()));
            } catch (SecurityException error) {
                throw new IllegalArgumentException("baseUrl resolves to a private or local address", error);
            } catch (java.net.UnknownHostException error) {
                throw new IllegalArgumentException("baseUrl host cannot be resolved", error);
            }
        }
        String normalized = parsed.toString();
        this.baseUrl = normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
        this.baseUri = URI.create(this.baseUrl);
        this.out = out;
        this.schemaToken = schemaToken == null || schemaToken.isBlank() ? null : schemaToken;
    }

    private static boolean allowPrivateUrls() {
        return Boolean.parseBoolean(System.getProperty(
                McpPackager.ALLOW_PRIVATE_URL_PROPERTY,
                System.getenv().getOrDefault(McpPackager.ALLOW_PRIVATE_URL_ENV, "false")));
    }

    /**
     * Connection-time SSRF gate. Re-resolves the target host and rejects the request unless
     * every returned address is public, immediately before the HTTP send.
     *
     * <p>Why not true socket pinning: the JDK (11 through 21) exposes no supported per-client
     * hook to force {@code java.net.http.HttpClient} onto a specific resolved address —
     * {@code HttpClient.Builder.socketResolver(...)} was a rejected proposal and has never
     * shipped. Rewriting the URI to an IP literal would break TLS hostname verification for
     * HTTPS targets. This re-resolve-and-validate-at-connect approach is the strongest
     * publicly supported option: the JDK caches DNS answers (default policy caches positive
     * results for at least 30s regardless of record TTL), so the check and the socket's own
     * lookup almost always observe the same answer. Residual risk: a sub-millisecond race in
     * the rare case the cache expires between this call and the socket connect.</p>
     *
     * @throws SecurityException if any resolved address is private/local (fail-closed)
     */
    List<InetAddress> requirePublicAtConnect(String host) throws java.io.IOException {
        if (!enforcePublic) {
            return List.of();
        }
        List<InetAddress> resolved = dnsResolver.resolve(host);
        IpSafety.pinToPublic(resolved); // validates the full set; first entry is representative
        return resolved;
    }

    /**
     * Lazily-built shared client. Redirect following is disabled so a hostile response cannot
     * bounce the bridge to another origin; each request still passes the connect-time gate.
     */
    private HttpClient http() {
        HttpClient client = httpClient;
        if (client == null) {
            client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            httpClient = client;
        }
        return client;
    }

    public void run() throws Exception {
        loadSchema();
        McpProtocol protocol = new McpProtocol(out, "aura-mcp-bridge", (method, params) -> switch (method) {
            case "tools/list" -> handleToolsList();
            case "tools/call" -> handleToolsCall(params);
            case "resources/list" -> Map.of("resources", List.of());
            case "resources/read" -> Map.of("contents", List.of());
            case "prompts/list" -> Map.of("prompts", List.of());
            case "prompts/get" -> Map.of("messages", List.of());
            default -> null;
        });
        protocol.runStdio();
    }

    private Map<String, Object> handleToolsList() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (int i = 0; i < routes.size(); i++) {
            JSONObject route = routes.getJSONObject(i);
            String unusable = unusableBecause(route);
            if (unusable != null) {
                log().warning("MCP bridge skipped " + route.getString("method") + " "
                        + route.getString("path") + ": " + unusable);
                continue;
            }
            String httpMethod = route.getString("method");
            String path = route.getString("path");
            String desc = route.getString("description");

            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", buildToolName(httpMethod, path));
            if (desc != null) tool.put("description", desc);
            tool.put("inputSchema", buildInputSchema(route.getJSONArray("params")));
            tools.add(tool);
        }
        return Map.of("tools", tools);
    }

    private Map<String, Object> handleToolsCall(JSONObject params) {
        String toolName = params.getString("name");
        JSONObject args = params.getJSONObject("arguments");

        for (int i = 0; i < routes.size(); i++) {
            JSONObject route = routes.getJSONObject(i);
            String name = buildToolName(route.getString("method"), route.getString("path"));
            if (!name.equals(toolName)) continue;
            // Re-checked here so a client cannot reach a route that tools/list refused.
            if (unusableBecause(route) != null) return McpContent.error("MCP_TOOL_NOT_FOUND");

            try {
                String result = callHttp(route, args);
                return McpContent.result(result);
            } catch (UpstreamStatus failure) {
                log().warning("MCP bridge tool " + toolName + " upstream returned HTTP " + failure.status());
                return McpContent.error("MCP_UPSTREAM_HTTP_" + failure.status());
            } catch (McpMissingArgument failure) {
                log().warning("MCP bridge tool " + toolName + " missing required argument");
                return McpContent.error("MCP_MISSING_ARGUMENT");
            } catch (Exception e) {
                log().warning("MCP bridge tool invocation failed for " + toolName + ": " + e.getClass().getSimpleName());
                return McpContent.error("MCP_UPSTREAM_ERROR");
            }
        }
        return McpContent.error("MCP_TOOL_NOT_FOUND");
    }

    private String callHttp(JSONObject route, JSONObject args) throws Exception {
        String httpMethod = route.getString("method");
        String path = route.getString("path");
        JSONArray params = route.getJSONArray("params");

        // replace path params and build query string
        StringBuilder query = new StringBuilder();
        String body = null;

        if (params != null && args != null) {
            for (int i = 0; i < params.size(); i++) {
                JSONObject p = params.getJSONObject(i);
                String pName = p.getString("name");
                String source = p.getString("source");
                Object val = args.get(pName);
                if (val == null) {
                    // Skipping a required argument produces a request the server rejects for
                    // a reason the model never learns; the name is the only useful feedback.
                    if (Boolean.TRUE.equals(p.getBoolean("required"))) throw new McpMissingArgument(pName);
                    continue;
                }

                if ("path".equals(source)) {
                    path = path.replace("{" + pName + "}", encode(val.toString()));
                } else if ("query".equals(source)) {
                    query.append(query.isEmpty() ? "?" : "&")
                            .append(encode(pName)).append("=").append(encode(val.toString()));
                } else if ("body".equals(source)) {
                    body = args.toJSONString();
                }
            }
        }

        URI target = resolveRouteUri(path, query.toString());
        requirePublicAtConnect(target.getHost());
        HttpRequest.Builder request = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(30));
        request.method(httpMethod, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) request.header("Content-Type", "application/json");

        HttpResponse<InputStream> response = http().send(request.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        try (InputStream is = response.body()) {
            String text = is != null ? readBounded(is, MAX_RESPONSE_BYTES) : "";
            if (status >= 400) throw new UpstreamStatus(status);
            return text;
        }
    }

    private java.util.logging.Logger log() {
        return java.util.logging.Logger.getLogger(McpBridge.class.getName());
    }

    /**
     * Why this route cannot become a tool, or null when it can. The bridge carries no user
     * identity and reads one bounded body, so an authenticated or streaming route is not a
     * tool that happens to fail: it is a choice a model should never have been offered.
     * A parameter the bridge cannot transmit is the same case one level down.
     */
    private static String unusableBecause(JSONObject route) {
        if (io.aura.contract.Route.WEBSOCKET.equals(route.getString("method"))) {
            return "route is a WebSocket endpoint, not an HTTP request";
        }
        if (route.getBooleanValue("authenticated")) return "route requires authentication the bridge cannot supply";
        if (route.getBooleanValue("streaming")) return "route streams a response the bridge cannot read";
        JSONArray params = route.getJSONArray("params");
        if (params != null) {
            for (int i = 0; i < params.size(); i++) {
                JSONObject parameter = params.getJSONObject(i);
                String source = parameter.getString("source");
                if (!"path".equals(source) && !"query".equals(source) && !"body".equals(source)) {
                    return "parameter " + parameter.getString("name") + " has no transport the bridge can use";
                }
            }
        }
        return null;
    }

    /** Upstream HTTP failure, reported as a status code rather than as upstream text. */
    private static final class UpstreamStatus extends IOException {
        private final int status;

        private UpstreamStatus(int status) {
            super("upstream returned HTTP " + status);
            this.status = status;
        }

        private int status() { return status; }
    }

    private URI resolveRouteUri(String path, String query) {
        if (path == null || path.isBlank() || !path.startsWith("/")
                || path.startsWith("//") || path.indexOf('\\') >= 0
                || path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
            throw new IllegalArgumentException("Schema route path must be an absolute path without query or fragment");
        }
        URI target = URI.create(baseUrl + path + (query == null ? "" : query)).normalize();
        if (!baseUri.getScheme().equalsIgnoreCase(target.getScheme())
                || !java.util.Objects.equals(baseUri.getHost(), target.getHost())
                || effectivePort(baseUri) != effectivePort(target)
                || target.getUserInfo() != null || target.getRawFragment() != null) {
            throw new IllegalArgumentException("Schema route resolves outside the bridge origin");
        }
        String basePath = baseUri.getRawPath();
        if (basePath == null || basePath.isEmpty() || "/".equals(basePath)) basePath = "";
        else if (basePath.endsWith("/")) basePath = basePath.substring(0, basePath.length() - 1);
        String targetPath = target.getRawPath();
        if (!basePath.isEmpty()
                && !targetPath.equals(basePath)
                && !targetPath.startsWith(basePath + "/")) {
            throw new IllegalArgumentException("Schema route escapes the configured base path");
        }
        return target;
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private void loadSchema() throws Exception {
        URI target = URI.create(baseUrl + "/__schema__");
        requirePublicAtConnect(target.getHost());
        HttpRequest.Builder request = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (schemaToken != null) request.header("X-Aura-Schema-Token", schemaToken);
        HttpResponse<InputStream> response = http().send(request.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            try (InputStream ignored = response.body()) { /* drain */ }
            throw new IOException("Schema request failed with HTTP " + response.statusCode());
        }
        try (InputStream is = response.body()) {
            String json = readBounded(is, MAX_RESPONSE_BYTES);
            JSONObject schema = JSON.parseObject(json);
            this.routes = schema.getJSONArray("routes");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String configuredSchemaToken() {
        String value = System.getProperty("aura.schema.auth-token");
        if (value == null) value = System.getProperty("aura.schema-token");
        if (value == null) value = System.getenv("AURA_SCHEMA_TOKEN");
        return value;
    }

    private static String readBounded(InputStream input, int maxBytes) throws IOException {
        byte[] bytes = input.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) throw new IOException("HTTP response exceeds maximum size");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String buildToolName(String method, String path) {
        return McpUtil.buildToolName(method, path);
    }

    private static Map<String, Object> buildInputSchema(JSONArray params) {
        List<McpParam> list = new ArrayList<>();
        if (params != null) {
            for (int i = 0; i < params.size(); i++) {
                JSONObject p = params.getJSONObject(i);
                String source = p.getString("source");
                Boolean required = p.getBoolean("required");
                @SuppressWarnings("unchecked")
                Map<String, Object> bodySchema = p.getJSONObject("schema") == null
                        ? Map.of() : p.getJSONObject("schema").toJavaObject(Map.class);
                list.add(new McpParam(new io.aura.contract.ApiParameter(p.getString("name"),
                        p.getString("type"), source, required == null || required,
                        p.getString("description"), bodySchema)));
            }
        }
        return McpUtil.inputSchema(list);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: java -jar aura-mcp-bridge.jar <base-url>");
            System.err.println("Example: java -jar aura-mcp-bridge.jar http://localhost:8080");
            System.exit(1);
        }
        new McpBridge(args[0]).run();
    }
}
