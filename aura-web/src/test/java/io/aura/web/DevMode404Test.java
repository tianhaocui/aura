package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DevMode404Test {

    @BeforeEach
    void setUp() {
        Aura.create().dev(true);
    }

    @AfterEach
    void tearDown() {
        Aura.create().dev(false);
    }

    @Test
    void devModeReturnsJsonDiagnostic() {
        Aura app = Aura.create();
        app.dev(true);
        app.get("/api/users", (BaseHandler) ctx -> ctx.json("ok"));
        app.post("/api/users", (BaseHandler) ctx -> ctx.json("ok"));

        TestClient client = TestClient.of(app);
        TestClient.Response res = client.get("/api/user").execute();

        assertEquals(404, res.status());
        String body = res.body();
        assertTrue(body.contains("No route matched"));
        assertTrue(body.contains("/api/user"));
        assertTrue(body.contains("registered"));
    }

    @Test
    void devModeShowsHintForSimilarRoute() {
        Aura app = Aura.create();
        app.dev(true);
        app.post("/api/users", (BaseHandler) ctx -> ctx.json("ok"));

        TestClient client = TestClient.of(app);
        TestClient.Response res = client.post("/api/user").execute();

        assertEquals(404, res.status());
        assertTrue(res.body().contains("Did you mean"));
    }
}
