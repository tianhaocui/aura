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

        // try to get app name from schema
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
                  "files": ["index.js", "bridge.jar"],
                  "keywords": ["mcp", "aura", "ai"],
                  "license": "MIT"
                }
                """.formatted(npmName, version, appName, binName);
        Files.writeString(out.resolve("package.json"), packageJson);

        // index.js with baked-in URL
        String indexJs = """
                #!/usr/bin/env node
                const { spawn } = require('child_process');
                const path = require('path');

                const jar = path.join(__dirname, 'bridge.jar');
                const url = process.env.API_URL || '%s';

                const child = spawn('java', ['-jar', jar, url], {
                  stdio: ['pipe', 'pipe', 'inherit']
                });

                process.stdin.pipe(child.stdin);
                child.stdout.pipe(process.stdout);

                child.on('exit', (code) => process.exit(code || 0));
                process.on('SIGTERM', () => child.kill());
                process.on('SIGINT', () => child.kill());
                """.formatted(appUrl);
        Files.writeString(out.resolve("index.js"), indexJs);

        // copy bridge.jar from classpath
        try (InputStream is = McpPackager.class.getResourceAsStream("/aura-mcp-bridge.jar")) {
            if (is != null) {
                Files.copy(is, out.resolve("bridge.jar"), StandardCopyOption.REPLACE_EXISTING);
            } else {
                // fallback: look in current directory
                Path localJar = Path.of("aura-mcp-bridge.jar");
                if (Files.exists(localJar)) {
                    Files.copy(localJar, out.resolve("bridge.jar"), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        System.out.println("MCP npm package generated at: " + out.toAbsolutePath());
        System.out.println("To publish:");
        System.out.println("  cd " + out);
        System.out.println("  npm publish --access public");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: McpPackager <app-url> <npm-name> <output-dir>");
            System.err.println("Example: McpPackager http://localhost:8080 @myname/my-app-mcp ./mcp-npm");
            System.exit(1);
        }
        generate(args[0], args[1], args[2]);
    }
}
