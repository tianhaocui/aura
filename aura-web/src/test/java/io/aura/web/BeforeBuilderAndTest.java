package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BeforeBuilderAndTest {

    @Test
    void andReturnToAuraForChaining() {
        Aura app = Aura.create();
        Aura returned = app.before(ctx -> {}).exclude("/health").and();
        assertSame(app, returned);
    }

    @Test
    void fluentChainWorksEndToEnd() {
        Aura app = Aura.create();
        app.before(ctx -> ctx.header("X-Check", "1")).exclude("/skip").and()
                .get("/api/data", (BaseHandler) ctx -> ctx.text("ok"));

        TestClient client = TestClient.of(app);
        assertEquals(200, client.get("/api/data").execute().status());
    }
}
