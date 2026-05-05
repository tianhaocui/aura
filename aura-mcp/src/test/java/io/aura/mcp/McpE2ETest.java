package io.aura.mcp;

import io.aura.Aura;
import io.aura.web.Router;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class McpE2ETest {

    record User(int id, String name) {}

    public static class UserService {
        public User get(int id) { return new User(id, "user-" + id); }
        public List<User> list() { return List.of(new User(1, "Alice"), new User(2, "Bob")); }
    }

    public static void main(String[] args) throws Exception {
        var userService = new UserService();

        Aura app = Aura.create()
            .port(9093)
            .mcp(9094)
            .routes((Router r) -> {
                r.get("/user/{id}", userService, "get").describe("Get user by ID");
                r.get("/user", userService, "list").describe("List all users");
            });
        app.start();

        Thread.sleep(1500);

        // Test 1: HTTP still works
        String httpResult = httpGet("http://localhost:9093/user/42");
        System.out.println("HTTP GET /user/42: " + httpResult);
        assert httpResult.contains("user-42") : "HTTP broken";

        // Test 2: Connect to MCP SSE endpoint
        System.out.println("\nConnecting to MCP SSE...");
        var sseConn = (HttpURLConnection) new URL("http://localhost:9094/sse").openConnection();
        sseConn.setConnectTimeout(3000);
        sseConn.setReadTimeout(5000);
        BufferedReader sseReader = new BufferedReader(
                new InputStreamReader(sseConn.getInputStream(), StandardCharsets.UTF_8));

        // Read the endpoint event
        String[] endpointEvent = readSseEvent(sseReader);
        System.out.println("SSE event: " + endpointEvent[0]);
        System.out.println("SSE data: " + endpointEvent[1]);
        assert endpointEvent[0].contains("endpoint") : "Expected endpoint event";
        String messagesUrl = endpointEvent[1];
        System.out.println("Messages URL: " + messagesUrl);

        // Test 3: Initialize
        mcpCallAsync("http://localhost:9094" + messagesUrl,
                """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test"}}}
                """);
        String[] initEvent = readSseEvent(sseReader);
        System.out.println("\nInitialize response: " + initEvent[1]);
        assert initEvent[1].contains("aura-mcp") : "Initialize failed";

        // Test 4: List tools
        mcpCallAsync("http://localhost:9094" + messagesUrl,
                """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """);
        String[] toolsEvent = readSseEvent(sseReader);
        System.out.println("\nTools list: " + toolsEvent[1]);
        assert toolsEvent[1].contains("get_user_by_id") : "Tools list missing get_user_by_id";
        assert toolsEvent[1].contains("get_user") : "Tools list missing get_user";

        // Test 5: Call tool — get_user(id=42)
        mcpCallAsync("http://localhost:9094" + messagesUrl,
                """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_user_by_id","arguments":{"id":"42"}}}
                """);
        String[] callEvent = readSseEvent(sseReader);
        System.out.println("\nTool call result: " + callEvent[1]);
        assert callEvent[1].contains("user-42") : "Tool call failed";

        // Test 6: Call tool — list_user()
        mcpCallAsync("http://localhost:9094" + messagesUrl,
                """
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_user","arguments":{}}}
                """);
        String[] listEvent = readSseEvent(sseReader);
        System.out.println("\nList tool result: " + listEvent[1]);
        assert listEvent[1].contains("Alice") : "List tool failed";

        sseReader.close();
        System.out.println("\nAll MCP tests passed!");
        app.stop();
    }

    static String httpGet(String url) throws Exception {
        var conn = new URL(url).openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        try (var is = conn.getInputStream()) {
            return new String(is.readAllBytes());
        }
    }

    static void mcpCallAsync(String url, String body) {
        new Thread(() -> {
            try {
                var conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(5000);
                conn.getOutputStream().write(body.trim().getBytes(StandardCharsets.UTF_8));
                conn.getResponseCode();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    static String[] readSseEvent(BufferedReader reader) throws Exception {
        String event = null;
        String data = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("event: ")) {
                event = line.substring(7);
            } else if (line.startsWith("data: ")) {
                data = line.substring(6);
            } else if (line.isEmpty() && event != null) {
                return new String[]{event, data};
            }
        }
        return new String[]{event, data};
    }
}
