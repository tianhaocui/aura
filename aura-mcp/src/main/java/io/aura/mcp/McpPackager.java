package io.aura.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class McpPackager {

    public static void generate(String appUrl, String npmName, String outputDir) throws Exception {
        generate(appUrl, npmName, outputDir, null);
    }

    public static void generate(String appUrl, String npmName, String outputDir, McpRouter router) throws Exception {
        Path out = Path.of(outputDir);
        Files.createDirectories(out);

        String binName = npmName.contains("/") ? npmName.substring(npmName.lastIndexOf('/') + 1) : npmName;
        String version = "0.1.0";

        String appName = binName;
        if (router == null) {
            try {
                String schema;
                try (var is = java.net.URI.create(appUrl + "/__schema__").toURL().openStream()) {
                    schema = new String(is.readAllBytes());
                }
                JSONObject obj = JSON.parseObject(schema);
                if (obj.getString("name") != null) appName = obj.getString("name");
            } catch (Exception e) {
                System.err.println("Warning: could not fetch schema from " + appUrl + ": " + e.getMessage());
            }
        }

        // package.json
        String packageJson = """
                {
                  "name": "%s",
                  "version": "%s",
                  "description": "MCP server for %s",
                  "bin": {
                    "%s": "./index.js"
                  },
                  "files": ["index.js"],
                  "keywords": ["mcp", "aura", "ai"],
                  "license": "MIT",
                  "engines": { "node": ">=18" }
                }
                """.formatted(npmName, version, appName, binName);
        Files.writeString(out.resolve("package.json"), packageJson);

        if (router != null) {
            String schemaJson = JSON.toJSONString(router.buildSchema());
            String template = loadTemplate();
            String indexJs = template
                    .replace("__API_URL_PLACEHOLDER__", appUrl)
                    .replace("__SCHEMA_PLACEHOLDER__", schemaJson);
            Files.writeString(out.resolve("index.js"), indexJs);
        } else {
            String template = loadTemplate();
            String indexJs = template.replace("__API_URL_PLACEHOLDER__", appUrl);
            Files.writeString(out.resolve("index.js"), indexJs);
        }

        System.out.println("MCP npm package generated at: " + out.toAbsolutePath());
        System.out.println("To publish:");
        System.out.println("  cd " + out);
        System.out.println("  npm publish --access public");
        System.out.println();
        System.out.println("Users configure:");
        System.out.println("  { \"command\": \"npx\", \"args\": [\"" + npmName + "\"] }");
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
        int code = pb.start().waitFor();
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
        try {
            Process p = new ProcessBuilder(cmd).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
