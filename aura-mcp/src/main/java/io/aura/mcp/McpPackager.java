package io.aura.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class McpPackager {

    /** System property used to explicitly permit packaging against private/local app URLs. */
    public static final String ALLOW_PRIVATE_URL_PROPERTY = "aura.mcp.allow-private-url";
    /** Environment variable equivalent of {@link #ALLOW_PRIVATE_URL_PROPERTY}. */
    public static final String ALLOW_PRIVATE_URL_ENV = "AURA_MCP_ALLOW_PRIVATE_URL";

    private static final int MAX_SCHEMA_BYTES = 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final int MAX_PROCESS_OUTPUT_BYTES = 64 * 1024;
    private static final long PROCESS_TIMEOUT_MS = 10_000L;
    private static final long PUBLISH_TIMEOUT_MS = 5 * 60 * 1_000L;

    public static void generate(String appUrl, String npmName, String outputDir) throws Exception {
        generate(appUrl, npmName, outputDir, null, allowPrivateUrls());
    }

    /** Generates a package with an explicit private/local app URL policy. */
    public static void generate(String appUrl, String npmName, String outputDir,
                                boolean allowPrivateUrl) throws Exception {
        generate(appUrl, npmName, outputDir, null, allowPrivateUrl);
    }

    public static void generate(String appUrl, String npmName, String outputDir, McpRouter router) throws Exception {
        generate(appUrl, npmName, outputDir, router, allowPrivateUrls());
    }

    /** Generates a package with an optional in-memory schema and explicit URL policy. */
    public static void generate(String appUrl, String npmName, String outputDir, McpRouter router,
                                boolean allowPrivateUrl) throws Exception {
        URI validatedAppUrl = validateAppUrl(appUrl, allowPrivateUrl);
        Path out = validateOutputDir(outputDir);
        Files.createDirectories(out);
        ensureRealOutputInsideRoot(out);
        if (Files.exists(out.resolve("package.json")) || Files.exists(out.resolve("index.js"))) {
            throw new FileAlreadyExistsException("Refusing to overwrite generated package files in " + out);
        }

        String binName = npmName.contains("/") ? npmName.substring(npmName.lastIndexOf('/') + 1) : npmName;
        String version = McpProtocolConstants.SERVER_VERSION;

        String appName = binName;
        if (router == null) {
            try {
                String schema = fetchSchema(validatedAppUrl);
                JSONObject obj = JSON.parseObject(schema);
                if (obj.getString("name") != null) appName = obj.getString("name");
            } catch (Exception e) {
                System.err.println("Warning: could not fetch schema from " + appUrl + ": " + e.getMessage());
            }
        }

        // package.json
        validateNpmName(npmName);
        Map<String, Object> packageMetadata = new LinkedHashMap<>();
        packageMetadata.put("name", npmName);
        packageMetadata.put("version", version);
        packageMetadata.put("description", "MCP server for " + appName);
        packageMetadata.put("bin", Map.of(binName, "./index.js"));
        packageMetadata.put("files", List.of("index.js"));
        packageMetadata.put("keywords", List.of("mcp", "aura", "ai"));
        packageMetadata.put("license", "MIT");
        packageMetadata.put("engines", Map.of("node", ">=18"));
        atomicWriteNew(out.resolve("package.json"), JSON.toJSONString(packageMetadata));

        if (router != null) {
            String schemaJson = JSON.toJSONString(router.buildSchema());
            String template = loadTemplate();
            String indexJs = template
                    .replace("\"__API_URL_PLACEHOLDER__\"", JSON.toJSONString(validatedAppUrl.toString()))
                    .replace("__SCHEMA_PLACEHOLDER__", schemaJson);
            atomicWriteNew(out.resolve("index.js"), indexJs);
        } else {
            String template = loadTemplate();
            String indexJs = template.replace("\"__API_URL_PLACEHOLDER__\"",
                    JSON.toJSONString(validatedAppUrl.toString()));
            atomicWriteNew(out.resolve("index.js"), indexJs);
        }

        System.out.println("MCP npm package generated at: " + out.toAbsolutePath());
        System.out.println("To publish:");
        System.out.println("  cd " + out);
        System.out.println("  npm publish --access public");
        System.out.println();
        System.out.println("Users configure:");
        System.out.println("  { \"command\": \"npx\", \"args\": [\"" + npmName + "\"] }");
    }

    private static String fetchSchema(URI appUrl) throws IOException {
        URI schemaUri = URI.create(appUrl.toString() + (appUrl.toString().endsWith("/") ? "" : "/"))
                .resolve("__schema__");
        HttpURLConnection conn = (HttpURLConnection) schemaUri.toURL().openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(false);
        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) throw new IOException("Schema request failed with HTTP " + status);
        try (InputStream input = conn.getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_SCHEMA_BYTES + 1);
            if (bytes.length > MAX_SCHEMA_BYTES) throw new IOException("Schema response exceeds maximum size");
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Validates an app URL using the secure default policy. HTTP(S) URLs that resolve to
     * loopback, link-local, site-local, unique-local, multicast, or wildcard addresses are rejected.
     *
     * <p>Local development packaging can be explicitly enabled with
     * {@code -Daura.mcp.allow-private-url=true} or
     * {@code AURA_MCP_ALLOW_PRIVATE_URL=true}. The opt-in is intentionally process-wide
     * because this method is also used by the command-line packager.</p>
     */
    public static URI validateAppUrl(String appUrl) throws Exception {
        return validateAppUrl(appUrl, allowPrivateUrls());
    }

    /** Validates an app URL with an explicit private/local-address policy. */
    public static URI validateAppUrl(String appUrl, boolean allowPrivate) throws Exception {
        URI uri = URI.create(appUrl).normalize();
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("appUrl must be an absolute HTTP(S) URL without userinfo");
        }
        if (!allowPrivate) {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isNonPublicAddress(address)) {
                    throw new IllegalArgumentException("appUrl resolves to a private or local address");
                }
            }
        }
        return uri;
    }

    private static boolean isNonPublicAddress(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || isUniqueLocalAddress(address)
                || address.isMulticastAddress();
    }

    private static boolean isUniqueLocalAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    static Path validateOutputDir(String outputDir) throws IOException {
        Path root = configuredOutputRoot();
        Path out = Path.of(outputDir).toAbsolutePath().normalize();
        if (!out.startsWith(root)) throw new IOException("Output directory must be inside " + root);
        Path realRoot = root.toRealPath();
        ensureExistingOutputInsideRoot(realRoot, out);
        return out;
    }

    private static void ensureRealOutputInsideRoot(Path out) throws IOException {
        Path root = configuredOutputRoot().toRealPath();
        Path resolved = out.toRealPath();
        if (!resolved.startsWith(root)) throw new IOException("Output directory escapes configured root");
    }

    private static Path configuredOutputRoot() {
        return Path.of(System.getProperty("aura.mcp.output-root", "."))
                .toAbsolutePath().normalize();
    }

    /**
     * Checks symlinks in the existing portion of an output path before any missing
     * directories are created. A missing output tail is safe once its deepest existing
     * ancestor resolves inside the configured root.
     */
    private static void ensureExistingOutputInsideRoot(Path realRoot, Path out) throws IOException {
        Path existing = out;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
            if (existing == null) {
                throw new IOException("Output directory has no existing ancestor");
            }
        }
        Path resolved = existing.toRealPath();
        if (!resolved.startsWith(realRoot)) {
            throw new IOException("Output directory escapes configured root");
        }
    }

    private static boolean allowPrivateUrls() {
        return Boolean.parseBoolean(System.getProperty(ALLOW_PRIVATE_URL_PROPERTY,
                System.getenv().getOrDefault(ALLOW_PRIVATE_URL_ENV, "false")));
    }

    private static void validateNpmName(String npmName) {
        if (npmName == null || !npmName.matches("(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("Invalid npm package name");
        }
    }

    private static void atomicWriteNew(Path target, String content) throws IOException {
        if (Files.exists(target)) throw new FileAlreadyExistsException(target.toString());
        Path temp = Files.createTempFile(target.getParent(), ".aura-mcp-", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String loadTemplate() throws IOException {
        try (InputStream is = McpPackager.class.getResourceAsStream("/mcp-bridge-template.js")) {
            if (is == null) throw new IOException("mcp-bridge-template.js not found in classpath");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: McpPackager <app-url> <npm-name> <output-dir> [--publish] [--dry-run]");
            System.err.println("Example: McpPackager http://my-server:8080 @myname/my-app-mcp ./mcp-npm");
            System.exit(1);
        }

        boolean publish = false;
        boolean dryRun = false;
        for (String arg : args) {
            if ("--publish".equals(arg)) publish = true;
            if ("--dry-run".equals(arg)) dryRun = true;
        }

        generate(args[0], args[1], args[2]);

        if (publish || dryRun) {
            publish(Path.of(args[2]), dryRun);
        }
    }

    private static void publish(Path dir, boolean dryRun) throws Exception {
        if (!commandExists("npm")) {
            System.err.println("Error: 'npm' not found. Install Node.js first: https://nodejs.org");
            System.exit(1);
        }

        String whoami = exec("npm", "whoami");
        if (whoami == null || whoami.isBlank()) {
            System.err.println("Error: Not logged in to npm. Run 'npm login' first.");
            System.exit(1);
        }
        System.out.println("Publishing as: " + whoami.trim());

        if (dryRun) {
            System.out.println("\n[dry-run] Would publish:");
            System.out.println("  " + dir.resolve("package.json"));
            System.out.println("  " + dir.resolve("index.js"));
            String pkg = Files.readString(dir.resolve("package.json"));
            System.out.println("\n" + pkg);
            System.out.println("[dry-run] No changes made. Remove --dry-run to publish.");
            return;
        }

        System.out.println("Publishing...");
        ProcessBuilder pb = new ProcessBuilder("npm", "publish", "--access", "public")
                .directory(dir.toFile())
                .inheritIO();
        Process process = pb.start();
        if (!process.waitFor(PUBLISH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            System.err.println("npm publish timed out after " + PUBLISH_TIMEOUT_MS + "ms");
            System.exit(1);
            return;
        }
        int code = process.exitValue();
        if (code == 0) {
            System.out.println("Published successfully!");
        } else {
            System.err.println("npm publish failed with exit code " + code);
            System.exit(code);
        }
    }

    private static boolean commandExists(String cmd) {
        try {
            Process p = new ProcessBuilder("which", cmd).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String exec(String... cmd) {
        Process process = null;
        java.util.concurrent.FutureTask<String> output = null;
        try {
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            Process current = process;
            output = new java.util.concurrent.FutureTask<>(() -> readCapped(current.getInputStream()));
            Thread reader = new Thread(output, "aura-mcp-process-reader");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return output.get(PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            return null;
        }
    }

    private static String readCapped(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        int retained = 0;
        while ((read = input.read(buffer)) != -1) {
            if (retained < MAX_PROCESS_OUTPUT_BYTES) {
                int copy = Math.min(read, MAX_PROCESS_OUTPUT_BYTES - retained);
                output.write(buffer, 0, copy);
                retained += copy;
            }
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
