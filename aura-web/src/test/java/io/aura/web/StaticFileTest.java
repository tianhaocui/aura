package io.aura.web;

import io.aura.Aura;

public class StaticFileTest {
    public static void main(String[] args) throws Exception {
        Aura app = Aura.create()
            .port(9090)
            .staticFiles("/public")
            .routes((Router r) -> {
                r.get("/api/hello", ctx -> ctx.text("api response"));
            });
        app.start();

        Thread.sleep(1000);

        // Test 1: API route still works
        String r1 = httpGet("http://localhost:9090/api/hello");
        System.out.println("Test 1 - API route: " + r1);
        assert "api response".equals(r1) : "Expected 'api response', got: " + r1;

        // Test 2: Static file served
        String r2 = httpGet("http://localhost:9090/index.html");
        System.out.println("Test 2 - Static file: " + r2);
        assert r2.contains("Hello Static") : "Expected static content, got: " + r2;

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
