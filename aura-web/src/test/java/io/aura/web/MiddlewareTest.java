package io.aura.web;

import io.aura.Aura;

import java.util.ArrayList;
import java.util.List;

public class MiddlewareTest {

    static final List<String> log = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Aura app = Aura.create()
            .port(8081)
            .routes((Router r) -> {
                r.before(ctx -> log.add("global-before"));
                r.after(ctx -> log.add("global-after"));

                r.get("/hello", ctx -> {
                    log.add("handler");
                    ctx.text("hi");
                });

                r.group("/api", api -> {
                    api.before(ctx -> log.add("api-before"));
                    api.after(ctx -> log.add("api-after"));

                    api.get("/test", ctx -> {
                        log.add("api-handler");
                        ctx.text("api ok");
                    });
                });

                r.exception(RuntimeException.class, (e, ctx) -> {
                    ctx.status(400).text("error: " + e.getMessage());
                });

                r.get("/fail", ctx -> {
                    throw new RuntimeException("boom");
                });
            });
        app.start();

        Thread.sleep(1000);

        // Test 1: basic route with global middleware
        log.clear();
        String r1 = httpGet("http://localhost:8081/hello");
        System.out.println("Test 1 - /hello: " + r1);
        System.out.println("  Middleware order: " + log);
        assert "hi".equals(r1) : "Expected 'hi', got: " + r1;
        assert log.equals(List.of("global-before", "handler", "global-after")) :
                "Wrong order: " + log;

        // Test 2: group route with group + global middleware
        log.clear();
        String r2 = httpGet("http://localhost:8081/api/test");
        System.out.println("Test 2 - /api/test: " + r2);
        System.out.println("  Middleware order: " + log);
        assert "api ok".equals(r2) : "Expected 'api ok', got: " + r2;
        assert log.equals(List.of("global-before", "api-before", "api-handler", "global-after", "api-after")) :
                "Wrong order: " + log;

        // Test 3: exception handler
        log.clear();
        String r3 = httpGet("http://localhost:8081/fail");
        System.out.println("Test 3 - /fail: " + r3);
        assert "error: boom".equals(r3) : "Expected 'error: boom', got: " + r3;

        // Test 4: 404
        String r4 = httpGet("http://localhost:8081/notfound");
        System.out.println("Test 4 - /notfound: " + r4);
        assert "Not Found".equals(r4) : "Expected 'Not Found', got: " + r4;

        System.out.println("\nAll tests passed!");
        app.stop();
    }

    static String httpGet(String url) throws Exception {
        var conn = new java.net.URL(url).openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        try (var is = conn.getInputStream()) {
            return new String(is.readAllBytes());
        } catch (java.io.IOException e) {
            try (var es = ((java.net.HttpURLConnection) conn).getErrorStream()) {
                return es != null ? new String(es.readAllBytes()) : e.getMessage();
            }
        }
    }
}
