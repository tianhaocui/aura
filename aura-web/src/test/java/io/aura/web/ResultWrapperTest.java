package io.aura.web;

import io.aura.Aura;
import io.aura.Result;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

class ResultWrapperTest {

    @Test
    void resultWrapper_wrapsReturnValue() {
        Aura app = Aura.create().resultWrapper(Result::ok);
        app.get("/data", (Supplier<Map<String, String>>) () -> Map.of("key", "value"));

        var client = TestClient.of(app);
        var resp = client.get("/data").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).contains("\"code\"");
        assertThat(resp.body()).contains("\"data\"");
        assertThat(resp.body()).contains("\"key\"");
    }

    @Test
    void resultWrapper_notTriggeredForVoidHandler() {
        Aura app = Aura.create().resultWrapper(Result::ok);
        app.get("/void", (BaseHandler) ctx -> ctx.text("manual"));

        var client = TestClient.of(app);
        var resp = client.get("/void").execute();
        assertThat(resp.body()).isEqualTo("manual");
        assertThat(resp.body()).doesNotContain("\"code\"");
    }

    @Test
    void resultWrapper_notTriggeredWhenHandlerAlreadyResponded() {
        Aura app = Aura.create().resultWrapper(Result::ok);
        app.get("/already", (BaseHandler) ctx -> ctx.json(Map.of("direct", true)));

        var client = TestClient.of(app);
        var resp = client.get("/already").execute();
        assertThat(resp.body()).contains("\"direct\"");
        assertThat(resp.body()).doesNotContain("\"code\":0");
    }

    @Test
    void resultWrapper_handlesNullReturn() {
        Aura app = Aura.create().resultWrapper(Result::ok);
        app.get("/null", (Supplier<Object>) () -> null);

        var client = TestClient.of(app);
        var resp = client.get("/null").execute();
        assertThat(resp.body()).contains("\"code\"");
    }

    @Test
    void resultWrapper_exceptionBypassesWrapper() {
        Aura app = Aura.create().resultWrapper(Result::ok);
        app.get("/error", (Supplier<Object>) () -> { throw new IllegalArgumentException("bad input"); });

        var client = TestClient.of(app);
        var resp = client.get("/error").execute();
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body()).contains("bad input");
        assertThat(resp.body()).doesNotContain("\"code\":0");
    }

    @Test
    void noWrapper_returnValueSerializedDirectly() {
        Aura app = Aura.create();
        app.get("/data", (Supplier<Map<String, String>>) () -> Map.of("key", "value"));

        var client = TestClient.of(app);
        var resp = client.get("/data").execute();
        assertThat(resp.body()).contains("\"key\"");
        assertThat(resp.body()).doesNotContain("\"code\"");
    }
}
