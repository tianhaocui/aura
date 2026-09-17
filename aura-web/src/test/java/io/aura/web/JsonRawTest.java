package io.aura.web;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class JsonRawTest {

    @Test
    void jsonRaw_sendsStringWithoutSerialization() {
        String rawJson = "{\"already\":\"serialized\",\"count\":42}";
        Router router = new Router();
        router.get("/raw", ctx -> ctx.jsonRaw(rawJson));

        var client = TestApplications.client(router);
        var resp = client.get("/raw").execute();
        assertThat(resp.body()).isEqualTo(rawJson);
    }

    @Test
    void jsonRaw_doesNotAddExtraQuotes() {
        Router router = new Router();
        router.get("/raw", ctx -> ctx.jsonRaw("[1,2,3]"));

        var client = TestApplications.client(router);
        var resp = client.get("/raw").execute();
        assertThat(resp.body()).isEqualTo("[1,2,3]");
        // json() would serialize a String, adding quotes: "\"[1,2,3]\""
        assertThat(resp.body()).doesNotStartWith("\"");
    }

    @Test
    void jsonRaw_emptyObject() {
        Router router = new Router();
        router.get("/empty", ctx -> ctx.jsonRaw("{}"));

        var client = TestApplications.client(router);
        var resp = client.get("/empty").execute();
        assertThat(resp.body()).isEqualTo("{}");
    }

    @Test
    void jsonRaw_nullString() {
        Router router = new Router();
        router.get("/null", ctx -> ctx.jsonRaw("null"));

        var client = TestApplications.client(router);
        var resp = client.get("/null").execute();
        assertThat(resp.body()).isEqualTo("null");
    }

    @Test
    void jsonRaw_vsMockContext_preservesExactString() {
        var ctx = new MockContext(Map.of(), Map.of(), Map.of(), null, TestApplications.runtime());
        ctx.jsonRaw("{\"test\":true}");
        assertThat(ctx.responseBody).isEqualTo("{\"test\":true}");
    }
}
