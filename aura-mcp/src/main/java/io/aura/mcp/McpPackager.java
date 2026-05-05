package io.aura.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class McpPackager {

    public static void generate(String appUrl, String npmName, String outputDir) throws Exception {
        Path out = Path.of(outputDir);
        Files.createDirectories(out);

        String binName = npmName.contains("/") ? npmName.substring(npmName.lastIndexOf('/') + 1) : npmName;
        String version = "0.1.0";

        String appName = binName;
        try {
            String schema = new String(new URL(appUrl + "/__schema__").openStream().readAllBytes());
            JSONObject obj = JSON.parseObject(schema);
            if (obj.getString("name") != null) appName = obj.getString("name");
        } catch (Exception ignored) {}

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

        // read the Node.js bridge template and bake in the URL
        String template = new String(
                McpPackager.class.getResourceAsStream("/mcp-bridge-template.js").readAllBytes(),
                StandardCharsets.UTF_8);
        String indexJs = template.replace("__API_URL_PLACEHOLDER__", appUrl);
        Files.writeString(out.resolve("index.js"), indexJs);

        System.out.println("MCP npm package generated at: " + out.toAbsolutePath());
        System.out.println("To publish:");
        System.out.println("  cd " + out);
        System.out.println("  npm publish --access public");
        System.out.println();
        System.out.println("Users configure:");
        System.out.println("  { \"command\": \"npx\", \"args\": [\"" + npmName + "\"] }");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: McpPackager <app-url> <npm-name> <output-dir>");
            System.err.println("Example: McpPackager http://my-server:8080 @myname/my-app-mcp ./mcp-npm");
            System.exit(1);
        }
        generate(args[0], args[1], args[2]);
    }
}
