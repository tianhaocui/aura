package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class JsonRawTest {

    @Test
    void jsonRaw_sendsStringWithoutSerialization() {
        Aura app = Aura.create();
        String rawJson = "{\"already\":\"serialized\",\"count\":42}";
        app.get("/raw", (BaseHandler) ctx -> ctx.jsonRaw(rawJson));

        var client = TestClient.of(app);
        var resp = client.get("/raw").execute();
        assertThat(resp.body()).isEqualTo(rawJson);
    }

    @Test
    void jsonRaw_doesNotAddExtraQuotes() {
        Aura app = Aura.create();
        app.get("/raw", (BaseHandler) ctx -> ctx.jsonRaw("[1,2,3]"));

        var client = TestClient.of(app);
        var resp = client.get("/raw").execute();
        assertThat(resp.body()).isEqualTo("[1,2,3]");
        // json() would serialize a String, adding quotes: "\"[1,2,3]\""
        assertThat(resp.body()).doesNotStartWith("\"");
    }

    @Test
    void jsonRaw_emptyObject() {
        Aura app = Aura.create();
        app.get("/empty", (BaseHandler) ctx -> ctx.jsonRaw("{}"));

        var client = TestClient.of(app);
        var resp = client.get("/empty").execute();
        assertThat(resp.body()).isEqualTo("{}");
    }

    @Test
    void jsonRaw_nullString() {
        Aura app = Aura.create();
        app.get("/null", (BaseHandler) ctx -> ctx.jsonRaw("null"));

        var client = TestClient.of(app);
        var resp = client.get("/null").execute();
        assertThat(resp.body()).isEqualTo("null");
    }

    @Test
    void jsonRaw_vsMockContext_preservesExactString() {
        var ctx = new MockContext(Map.of(), Map.of(), Map.of(), null, Aura.create());
        ctx.jsonRaw("{\"test\":true}");
        assertThat(ctx.responseBody).isEqualTo("{\"test\":true}");
    }
}
