package io.aura.web;

import io.aura.Aura;
import io.aura.ExceptionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class ExceptionSnapshotTest {

    @Test
    void onException_capturesSnapshot() {
        var captured = new AtomicReference<ExceptionSnapshot>();
        var capturedException = new AtomicReference<Exception>();

        var app = Aura.create().env("dev")
                .onException((ex, snapshot) -> {
                    capturedException.set(ex);
                    captured.set(snapshot);
                });
        app.get("/fail", (BaseHandler) ctx -> {
            throw new RuntimeException("boom");
        });

        var client = TestClient.of(app);
        var resp = client.get("/fail").execute();
        assertThat(resp.status()).isEqualTo(500);

        assertThat(captured.get()).isNotNull();
        ExceptionSnapshot snap = captured.get();
        assertThat(snap.path()).isEqualTo("/fail");
        assertThat(snap.exceptionClass()).isEqualTo("java.lang.RuntimeException");
        assertThat(snap.message()).isEqualTo("boom");
        assertThat(snap.stackTrace()).contains("RuntimeException");
        assertThat(capturedException.get()).isInstanceOf(RuntimeException.class);
    }

    @Test
    void onException_capturesQueryAndPathParams() {
        var captured = new AtomicReference<ExceptionSnapshot>();

        var app = Aura.create().env("dev")
                .onException((ex, snapshot) -> captured.set(snapshot));
        app.get("/users/{id}", (BaseHandler) ctx -> {
            throw new IllegalArgumentException("bad id");
        });

        var client = TestClient.of(app);
        client.get("/users/42").query("verbose", "true").execute();

        ExceptionSnapshot snap = captured.get();
        assertThat(snap).isNotNull();
        assertThat(snap.pathParams()).containsEntry("id", "42");
        assertThat(snap.queryParams()).containsEntry("verbose", "true");
    }

    @Test
    void onException_capturesHeaders() {
        var captured = new AtomicReference<ExceptionSnapshot>();

        var app = Aura.create().env("dev")
                .onException((ex, snapshot) -> captured.set(snapshot));
        app.post("/data", (BaseHandler) ctx -> {
            throw new RuntimeException("fail");
        });

        var client = TestClient.of(app);
        client.post("/data").header("X-Custom", "test-val").body("{\"a\":1}").execute();

        ExceptionSnapshot snap = captured.get();
        assertThat(snap).isNotNull();
        assertThat(snap.headers()).containsEntry("X-Custom", "test-val");
        assertThat(snap.body()).isEqualTo("{\"a\":1}");
    }

    @Test
    void onException_notCalledOnSuccess() {
        var captured = new AtomicReference<ExceptionSnapshot>();

        var app = Aura.create().env("dev")
                .onException((ex, snapshot) -> captured.set(snapshot));
        app.get("/ok", (BaseHandler) ctx -> ctx.json("ok"));

        var client = TestClient.of(app);
        client.get("/ok").execute();

        assertThat(captured.get()).isNull();
    }

    @Test
    void onException_toJson_returnsValidJson() {
        var captured = new AtomicReference<ExceptionSnapshot>();

        var app = Aura.create().env("dev")
                .onException((ex, snapshot) -> captured.set(snapshot));
        app.get("/err", (BaseHandler) ctx -> {
            throw new RuntimeException("json test");
        });

        var client = TestClient.of(app);
        client.get("/err").execute();

        String json = captured.get().toJson();
        assertThat(json).contains("\"exceptionClass\":\"java.lang.RuntimeException\"");
        assertThat(json).contains("\"message\":\"json test\"");
        assertThat(json).contains("\"path\":\"/err\"");
        com.alibaba.fastjson2.JSONObject parsed = com.alibaba.fastjson2.JSON.parseObject(json);
        assertThat(parsed.getString("exceptionClass")).isEqualTo("java.lang.RuntimeException");
    }

    @Test
    void onException_toRow_returnsMap() {
        var captured = new AtomicReference<ExceptionSnapshot>();

        var app = Aura.create().env("dev")
                .onException((ex, snapshot) -> captured.set(snapshot));
        app.get("/row", (BaseHandler) ctx -> {
            throw new RuntimeException("row test");
        });

        var client = TestClient.of(app);
        client.get("/row").execute();

        var row = captured.get().toRow();
        assertThat(row).containsEntry("exception_class", "java.lang.RuntimeException");
        assertThat(row).containsEntry("message", "row test");
        assertThat(row).containsEntry("path", "/row");
        assertThat(row).containsKey("stack_trace");
    }

    @Test
    void onException_handlerException_doesNotPropagate() {
        var app = Aura.create().env("dev")
                .onException((ex, snapshot) -> {
                    throw new RuntimeException("handler itself throws");
                });
        app.get("/safe", (BaseHandler) ctx -> {
            throw new RuntimeException("original");
        });

        var client = TestClient.of(app);
        var resp = client.get("/safe").execute();
        assertThat(resp.status()).isEqualTo(500);
    }
}
