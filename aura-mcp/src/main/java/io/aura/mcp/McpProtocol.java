package io.aura.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class McpProtocol {

    private static final int DEFAULT_MAX_LINE_BYTES = 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(McpProtocol.class);

    @FunctionalInterface
    public interface RequestHandler {
        Object handle(String method, JSONObject params);
    }

    private final PrintStream out;
    private final String serverName;
    private final RequestHandler handler;
    private final int maxLineBytes;

    public McpProtocol(PrintStream out, String serverName, RequestHandler handler) {
        this(out, serverName, handler, DEFAULT_MAX_LINE_BYTES);
    }

    McpProtocol(PrintStream out, String serverName, RequestHandler handler, int maxLineBytes) {
        this.out = out;
        this.serverName = serverName;
        this.handler = handler;
        this.maxLineBytes = Math.max(1024, maxLineBytes);
    }

    public void runStdio() throws Exception {
        run(System.in);
    }

    void run(InputStream input) throws Exception {
        String line;
        while ((line = readLine(input, maxLineBytes)) != null) {
            if (line.isBlank()) continue;
            try {
                JSONObject request = JSON.parseObject(line);
                JSONObject response = dispatch(request);
                if (response != null) {
                    out.println(response.toJSONString());
                    out.flush();
                }
            } catch (Exception e) {
                sendError(null, -32700, "Parse error: " + e.getMessage());
            }
        }
        log.info("MCP stdio stream closed");
    }

    private String readLine(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(limit, 8192));
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') break;
            if (bytes.size() >= limit) {
                while (value != '\n' && value != -1) value = input.read();
                sendError(null, -32600, "Request exceeds maximum size");
                return "";
            }
            if (value != '\r') bytes.write(value);
        }
        if (value == -1 && bytes.size() == 0) return null;
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private JSONObject dispatch(JSONObject request) {
        String method = request.getString("method");
        Object id = request.get("id");

        if (method == null) return null;

        Object result;
        if ("initialize".equals(method)) {
            result = Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of("tools", Map.of()),
                "serverInfo", Map.of("name", serverName, "version", "0.1.0")
            );
        } else if ("notifications/initialized".equals(method)) {
            result = null;
        } else {
            result = handler.handle(method, request.getJSONObject("params"));
        }

        if (id == null || result == null) return null;

        JSONObject resp = new JSONObject();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("result", result);
        return resp;
    }

    private void sendError(Object id, int code, String message) {
        JSONObject err = new JSONObject();
        err.put("jsonrpc", "2.0");
        err.put("id", id);
        err.put("error", Map.of("code", code, "message", message));
        out.println(err.toJSONString());
        out.flush();
    }
}
