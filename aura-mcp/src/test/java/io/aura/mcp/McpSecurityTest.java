package io.aura.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.*;

class McpSecurityTest {

    @TempDir
    Path tempDir;

    @Test
    void protocolRejectsOversizedJsonLine() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        McpProtocol protocol = new McpProtocol(
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                "test", (method, params) -> null, 1024);

        protocol.run(new ByteArrayInputStream(("x".repeat(1025) + "\n")
                .getBytes(StandardCharsets.UTF_8)));

        assertThat(bytes.toString(StandardCharsets.UTF_8))
                .contains("Request exceeds maximum size");
    }

    @Test
    void bridgeSendsSchemaTokenWhenConfigured() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicBoolean tokenSeen = new AtomicBoolean();
        server.createContext("/__schema__", exchange -> {
            tokenSeen.set("schema-secret".equals(exchange.getRequestHeaders().getFirst("X-Aura-Schema-Token")));
            byte[] body = "{\"routes\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        InputStream originalIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}\n"
                    .getBytes(StandardCharsets.UTF_8)));
            new McpBridge("http://localhost:" + server.getAddress().getPort(),
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                    "schema-secret", true).run();
            assertThat(tokenSeen.get()).isTrue();
        } finally {
            System.setIn(originalIn);
            server.stop(0);
        }
    }

    @Test
    void packagerRejectsUnsafeUrlsByDefault() {
        assertThatThrownBy(() -> McpPackager.validateAppUrl("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> McpPackager.validateAppUrl("http://user:pass@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bridgeRejectsPrivateTargetsUnlessExplicitlyAllowed() {
        assertThatThrownBy(() -> new McpBridge("http://127.0.0.1:8080",
                new PrintStream(new ByteArrayOutputStream()), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private or local");
        assertThatThrownBy(() -> new McpBridge("https://example.test/api?x=1",
                new PrintStream(new ByteArrayOutputStream()), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
    }

    @Test
    void bridgeRejectsSchemaRoutesThatEscapeTheConfiguredOrigin() throws Exception {
        McpBridge bridge = new McpBridge("https://example.test", new PrintStream(new ByteArrayOutputStream()), null, true);
        java.lang.reflect.Method resolver = McpBridge.class
                .getDeclaredMethod("resolveRouteUri", String.class, String.class);
        resolver.setAccessible(true);
        assertThatThrownBy(() -> resolver.invoke(bridge, "//evil.test/steal", ""))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.invoke(bridge, "/orders?redirect=https://evil.test", ""))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        McpBridge prefixed = new McpBridge("https://example.test/api",
                new PrintStream(new ByteArrayOutputStream()), null, true);
        Object resolved = resolver.invoke(prefixed, "/orders", "");
        assertThat(resolved.toString()).isEqualTo("https://example.test/api/orders");
    }

    @Test
    void packagerRejectsNonPublicAddressLiteralsByDefault() {
        List<String> nonPublicUrls = List.of(
                "http://0.0.0.0",       // IPv4 any-local
                "http://127.0.0.1",     // IPv4 loopback
                "http://169.254.1.1",   // IPv4 link-local
                "http://10.0.0.1",      // IPv4 site-local
                "http://224.0.0.1",     // IPv4 multicast
                "http://[::]",          // IPv6 any-local
                "http://[::1]",         // IPv6 loopback
                "http://[fe80::1]",     // IPv6 link-local
                "http://[fec0::1]",     // IPv6 site-local
                "http://[fc00::1]",     // IPv6 unique-local
                "http://[fd00::1]",     // IPv6 unique-local
                "http://[ff02::1]");    // IPv6 multicast

        for (String url : nonPublicUrls) {
            assertThatThrownBy(() -> McpPackager.validateAppUrl(url))
                    .as("non-public URL %s", url)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("private or local");
        }
    }

    @Test
    void packagerAllowsLocalUrlOnlyWithExplicitOptIn() throws Exception {
        assertThat(McpPackager.validateAppUrl("http://localhost:8080", true))
                .hasToString("http://localhost:8080");
        assertThat(McpPackager.validateAppUrl("http://127.0.0.1:8080", true))
                .hasToString("http://127.0.0.1:8080");
        assertThat(McpPackager.validateAppUrl("http://[fd00::1]:8080", true))
                .hasToString("http://[fd00::1]:8080");
    }

    @Test
    void generationSerializesMetadataAndRefusesOverwrite() throws Exception {
        String oldRoot = System.getProperty("aura.mcp.output-root");
        try {
            System.setProperty("aura.mcp.output-root", tempDir.toString());
            Path output = tempDir.resolve("generated");
            McpRouter router = new McpRouter();
            router.tool("ping", "ping").handler(ctx -> "ok");

            McpPackager.generate("http://localhost:8080", "@aura/test-mcp", output.toString(), router, true);

            assertThat(Files.readString(output.resolve("package.json")))
                    .contains("\"name\":\"@aura/test-mcp\"")
                    .contains("\"version\":\"1.0.0\"");
            assertThat(Files.readString(output.resolve("index.js")))
                    .contains("return \"http://localhost:8080\"")
                    .contains("version: packageMetadata.version")
                    .doesNotContain("__API_URL_PLACEHOLDER__");
            assertThatThrownBy(() -> McpPackager.generate(
                    "http://localhost:8080", "@aura/test-mcp", output.toString(), router, true))
                    .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        } finally {
            restore("aura.mcp.output-root", oldRoot);
        }
    }

    @Test
    void outputMustRemainInsideConfiguredRoot() throws Exception {
        String oldRoot = System.getProperty("aura.mcp.output-root");
        try {
            System.setProperty("aura.mcp.output-root", tempDir.toString());
            assertThatThrownBy(() -> McpPackager.validateOutputDir(
                    tempDir.getParent().resolve("outside").toString()))
                    .isInstanceOf(java.io.IOException.class);
        } finally {
            restore("aura.mcp.output-root", oldRoot);
        }
    }

    @Test
    void outputSymlinkCannotEscapeRootBeforeDirectoriesAreCreated() throws Exception {
        String oldRoot = System.getProperty("aura.mcp.output-root");
        String oldPrivate = System.getProperty(McpPackager.ALLOW_PRIVATE_URL_PROPERTY);
        try {
            Path root = tempDir.resolve("root");
            Path outside = tempDir.resolve("outside");
            Files.createDirectories(root);
            Files.createDirectories(outside);
            Path link = root.resolve("escape");
            try {
                Files.createSymbolicLink(link, outside);
            } catch (UnsupportedOperationException | java.io.IOException e) {
                Assumptions.abort("Symbolic links are unavailable: " + e.getMessage());
            }

            System.setProperty("aura.mcp.output-root", root.toString());
            System.setProperty(McpPackager.ALLOW_PRIVATE_URL_PROPERTY, "true");
            Path output = link.resolve("generated");
            McpRouter router = new McpRouter();
            router.tool("ping", "ping").handler(ctx -> "ok");

            assertThatThrownBy(() -> McpPackager.generate(
                    "http://localhost:8080", "@aura/test-mcp", output.toString(), router))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining("escapes configured root");
            assertThat(Files.exists(output)).isFalse();
            assertThat(Files.exists(outside.resolve("generated"))).isFalse();
        } finally {
            restore("aura.mcp.output-root", oldRoot);
            restore(McpPackager.ALLOW_PRIVATE_URL_PROPERTY, oldPrivate);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
