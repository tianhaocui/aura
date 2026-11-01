package io.aura.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bridge turns one route contract into tool choices. A route it cannot actually call is
 * not a tool that fails at call time -- it is a wrong option handed to the model, and a
 * refused tool must stay refused when a client guesses its name.
 */
class McpBridgeToolTest {

    private static final String SCHEMA = """
            {"routes":[
              {"method":"GET","path":"/orders/{id}","description":"Read an order",
               "params":[{"name":"id","type":"long","source":"path","required":true}],
               "authenticated":false},
              {"method":"GET","path":"/secret","description":"Needs a user",
               "params":[],"authenticated":true,"permission":"secrets.read"},
              {"method":"GET","path":"/events","description":"Streams",
               "params":[],"authenticated":false,"streaming":true},
              {"method":"POST","path":"/headerful",
               "params":[{"name":"trace","type":"string","source":"header","required":false}],
               "authenticated":false},
              {"method":"WS","path":"/ws/room/{roomId}","description":"Chat room",
               "params":[{"name":"roomId","type":"String","source":"path","required":true}],
               "authenticated":false}
            ]}""";

    @Test
    void advertisesOnlyRoutesTheBridgeCanCall() throws Exception {
        String output = bridge(SCHEMA, 200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");

        List<String> names = toolNames(output);
        assertThat(names).containsExactly("get_orders_by_id");
    }

    @Test
    void anUnadvertisedRouteStaysUnreachableEvenWhenNamed() throws Exception {
        String output = bridge(SCHEMA, 200,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_secret\",\"arguments\":{}}}");

        assertThat(result(output, 1).getString("code")).isEqualTo("MCP_TOOL_NOT_FOUND");
    }

    @Test
    void upstreamFailureIsAToolErrorAndKeepsItsDetailsUpstream() throws Exception {
        String output = bridge(SCHEMA, 500,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_orders_by_id\",\"arguments\":{\"id\":\"7\"}}}");

        JSONObject result = result(output, 1);
        assertThat(result.getBoolean("isError")).isTrue();
        assertThat(result.getString("code")).isEqualTo("MCP_UPSTREAM_HTTP_500");
        assertThat(output).doesNotContain("must not reach the model");
    }

    @Test
    void missingRequiredArgumentIsNamedInsteadOfSilentlyDropped() throws Exception {
        String output = bridge(SCHEMA, 200,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_orders_by_id\",\"arguments\":{}}}");

        assertThat(result(output, 1).getString("code")).isEqualTo("MCP_MISSING_ARGUMENT");
    }

    /**
     * The schema marks a WebSocket endpoint as streaming, which is enough to decline it; the
     * method is checked as well so a document that omits that flag cannot hand the bridge an
     * upgrade request it has no way to answer.
     */
    @Test
    void webSocketEndpointIsNeitherAdvertisedNorCallable() throws Exception {
        String output = bridge(SCHEMA, 200,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"ws_ws_room_by_id\",\"arguments\":{\"roomId\":\"lobby\"}}}");

        assertThat(toolNames(output)).noneMatch(name -> name.startsWith("ws_"));
        assertThat(result(output, 2).getString("code")).isEqualTo("MCP_TOOL_NOT_FOUND");
    }

    @Test
    void successfulCallStillReturnsTheUpstreamBody() throws Exception {        String output = bridge(SCHEMA, 200,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_orders_by_id\",\"arguments\":{\"id\":\"7\"}}}");

        JSONObject result = result(output, 1);
        assertThat(result.containsKey("isError")).isFalse();
        assertThat(result.getJSONArray("content").getJSONObject(0).getString("text"))
                .contains("upstream body for /orders/7");
    }

    private static String bridge(String schema, int upstreamStatus, String... requests) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        byte[] schemaBody = schema.getBytes(StandardCharsets.UTF_8);
        server.createContext("/__schema__", exchange -> {
            exchange.sendResponseHeaders(200, schemaBody.length);
            try (var output = exchange.getResponseBody()) {
                output.write(schemaBody);
            }
        });
        server.createContext("/", exchange -> {
            byte[] body = ("upstream body for " + exchange.getRequestURI()
                    + " that must not reach the model").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(upstreamStatus, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();

        StringBuilder input = new StringBuilder();
        for (String request : requests) input.append(request).append('\n');
        InputStream originalIn = System.in;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream(input.toString().getBytes(StandardCharsets.UTF_8)));
            new McpBridge("http://localhost:" + server.getAddress().getPort(),
                    new PrintStream(bytes, true, StandardCharsets.UTF_8), null, true).run();
        } finally {
            System.setIn(originalIn);
            server.stop(0);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static List<String> toolNames(String output) {
        List<String> names = new ArrayList<>();
        result(output, 1).getJSONArray("tools")
                .forEach(tool -> names.add(((JSONObject) tool).getString("name")));
        return names;
    }

    private static JSONObject result(String output, int id) {
        for (String line : output.split("\\R")) {
            if (line.isBlank()) continue;
            JSONObject message = JSON.parseObject(line);
            if (message.getIntValue("id") == id) return message.getJSONObject("result");
        }
        throw new AssertionError("bridge returned no response for id " + id + ": " + output);
    }
}
