package io.aura.web;

import
io.aura.port.SseEmitter;
import io.aura.Validate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class TestClientTest {

    private static TestClient clientWithRoutes() {
        Router router = new Router();
        router.get("/hello", ctx -> ctx.text("world"));
        router.post("/echo", ctx -> ctx.text(ctx.body(String.class)));
        router.get("/greet", ctx -> ctx.text("hi " + ctx.query("name")));
        router.get("/boom", ctx -> { throw new IllegalArgumentException("oops"); });
        return TestApplications.client(router);
    }

    @Test
    void get_returns200WithCorrectBody() {
        TestClient client = clientWithRoutes();
        TestClient.Response resp = client.get("/hello").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("world");
    }

    @Test
    void post_withBody_works() {
        TestClient client = clientWithRoutes();
        TestClient.Response resp = client.post("/echo").body("\"hello\"").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).contains("hello");
    }

    @Test
    void unknownPath_returns404() {
        TestClient client = clientWithRoutes();
        TestClient.Response resp = client.get("/not-found").execute();
        assertThat(resp.status()).isEqualTo(404);
    }

    @Test
    void queryParams_parsedCorrectly() {
        TestClient client = clientWithRoutes();
        TestClient.Response resp = client.get("/greet?name=Alice").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("hi Alice");
    }

    @Test
    void exceptionHandler_invoked() {
        TestClient client = clientWithRoutes();
        TestClient.Response resp = client.get("/boom").execute();
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body()).contains("oops");
    }

    @Test
    void schemaAndOpenApiUseTheSameCompiledRouteContract() {
        Router router = new Router();
        router.get("/orders/{id}", ctx -> ctx.text("ok"))
                .describe("Get order")
                .param("id", "long", "path", true, "Order identifier");
        TestClient client = TestApplications.client(router);

        com.alibaba.fastjson2.JSONObject route = SchemaWire.route(client, "/orders/{id}");
        assertThat(route).containsEntry("method", "GET")
                .containsEntry("description", "Get order")
                // A lambda route declares no return type, and undeclared metadata
                // stays out of the document instead of arriving as a null.
                .doesNotContainKey("returnType");
        assertThat(route.getString("example")).contains("/orders/1");
        assertThat(SchemaWire.parameter(route, "id"))
                .containsEntry("source", "path")
                .containsEntry("required", true)
                .containsEntry("description", "Order identifier");

        assertThat(SchemaWire.operation(client, "/orders/{id}", "GET").getString("operationId"))
                .isEqualTo("get_orders_id");
        // The same two facts about the same parameter, in both documents.
        assertThat(SchemaWire.queryParameters(client, "/orders/{id}", "GET").getJSONObject(0))
                .containsEntry("name", "id")
                .containsEntry("in", "path")
                .containsEntry("required", true);
    }

    @Test
    void expect_throwsAssertionErrorOnMismatch() {
        TestClient client = clientWithRoutes();
        assertThatThrownBy(() -> client.get("/hello").expect(404))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expected 404");
    }

    @Test
    void bodyContains_throwsOnMissingText() {
        TestClient client = clientWithRoutes();
        TestClient.Response resp = client.get("/hello").execute();
        assertThatThrownBy(() -> resp.bodyContains("missing-text"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("missing-text");
    }

    @Test
    void expect_onResponse_passesWhenStatusMatches() {
        TestClient client = clientWithRoutes();
        assertThatCode(() -> client.get("/hello").execute().expect(200))
                .doesNotThrowAnyException();
    }

    @Test
    void validationException_returns400() {
        Router router = new Router();
        router.get("/validate", ctx -> {
            Validate.notBlank("", "name is required");
        });
        TestClient client = TestApplications.client(router);
        TestClient.Response resp = client.get("/validate").execute();
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body()).contains("name is required");
    }

    @Test
    void illegalArgumentException_returns400() {
        Router router = new Router();
        router.get("/bad-param", ctx -> {
            throw new IllegalArgumentException("Invalid integer: abc");
        });
        TestClient client = TestApplications.client(router);
        TestClient.Response resp = client.get("/bad-param").execute();
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body()).contains("Invalid integer");
    }

    // --- PUT / DELETE ---

    @Test
    void put_routeHandled() {
        Router router = new Router();
        router.put("/item/{id}", ctx -> ctx.text("updated " + ctx.path("id")));
        TestClient client = TestApplications.client(router);
        TestClient.Response resp = client.put("/item/42").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("updated 42");
    }

    @Test
    void delete_routeHandled() {
        Router router = new Router();
        router.delete("/item/{id}", ctx -> ctx.status(204).text(""));
        TestClient client = TestApplications.client(router);
        TestClient.Response resp = client.delete("/item/7").execute();
        assertThat(resp.status()).isEqualTo(204);
    }

    // --- route priority: exact path beats param path ---

    @Test
    void exactPath_beatsParamPath_regardlessOfRegistrationOrder() {
        Router router = new Router();
        // register param route first — exact should still win
        router.get("/item/{id}", ctx -> ctx.text("param:" + ctx.path("id")));
        router.get("/item/search", ctx -> ctx.text("exact:search"));
        TestClient client = TestApplications.client(router);

        TestClient.Response exact = client.get("/item/search").execute();
        assertThat(exact.body()).isEqualTo("exact:search");

        TestClient.Response param = client.get("/item/123").execute();
        assertThat(param.body()).isEqualTo("param:123");
    }

    // --- error response is JSON ---

    @Test
    void unhandledException_returnsJsonError() {
        Router router = new Router();
        router.get("/crash", ctx -> { throw new RuntimeException("boom"); });
        TestClient client = TestApplications.client(router);
        TestClient.Response resp = client.get("/crash").execute();
        assertThat(resp.status()).isEqualTo(500);
        assertThat(resp.body()).contains("\"error\"");
        assertThat(resp.body()).contains("boom");
    }

    @Test
    void validationError_returnsJsonWithErrorKey() {
        Router router = new Router();
        router.get("/v", ctx -> { throw new IllegalArgumentException("bad input"); });
        TestClient client = TestApplications.client(router);
        TestClient.Response resp = client.get("/v").execute();
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body()).contains("\"error\"");
        assertThat(resp.body()).contains("bad input");
    }

    // --- redirect CRLF guard ---

    @Test
    void redirect_crlf_throwsIllegalArgument() {
        // MockContext.redirect does not validate, so exercise the real Context guard directly.
        assertThatThrownBy(() -> {
            Context ctx = new Context(null, java.util.Map.of(), TestApplications.runtime(), null);
            ctx.redirect("/safe\r\nX-Injected: evil");
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid redirect URL");
    }

    // --- pagination helpers ---

    @Test
    void pageNum_defaultsTo1_whenAbsent() {
        Router router = new Router();
        router.get("/page", ctx -> ctx.text(String.valueOf(ctx.pageNum())));
        TestClient client = TestApplications.client(router);
        assertThat(client.get("/page").execute().body()).isEqualTo("1");
    }

    @Test
    void pageNum_rejectsNonPositiveValues() {
        Router router = new Router();
        router.get("/page", ctx -> ctx.text(String.valueOf(ctx.pageNum())));
        TestClient client = TestApplications.client(router);
        assertThat(client.get("/page?page=0").execute().status()).isEqualTo(400);
        assertThat(client.get("/page?page=-5").execute().status()).isEqualTo(400);
    }

    @Test
    void pageNum_rejectsInvalidString() {
        Router router = new Router();
        router.get("/page", ctx -> ctx.text(String.valueOf(ctx.pageNum())));
        TestClient client = TestApplications.client(router);
        assertThat(client.get("/page?page=abc").execute().status()).isEqualTo(400);
    }

    @Test
    void pageSize_defaultsTo20_whenAbsent() {
        Router router = new Router();
        router.get("/page", ctx -> ctx.text(String.valueOf(ctx.pageSize())));
        TestClient client = TestApplications.client(router);
        assertThat(client.get("/page").execute().body()).isEqualTo("20");
    }

    @Test
    void pageSize_rejectsAboveMax500() {
        Router router = new Router();
        router.get("/page", ctx -> ctx.text(String.valueOf(ctx.pageSize())));
        TestClient client = TestApplications.client(router);
        assertThat(client.get("/page?pageSize=9999").execute().status()).isEqualTo(400);
    }

    @Test
    void pageSize_rejectsBelowMin1() {
        Router router = new Router();
        router.get("/page", ctx -> ctx.text(String.valueOf(ctx.pageSize())));
        TestClient client = TestApplications.client(router);
        assertThat(client.get("/page?pageSize=0").execute().status()).isEqualTo(400);
    }

    // --- SSE ---

    @Test
    void sse_sendsDataEvents() throws Exception {
        Router router = new Router();
        router.get("/stream", ctx -> {
            SseEmitter sse = ctx.sse();
            sse.send("hello");
            sse.send("world");
            sse.close();
        });
        TestClient client = TestApplications.client(router);
        TestClient.Response resp = client.get("/stream").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).contains("data: hello");
        assertThat(resp.body()).contains("data: world");
    }

    @Test
    void sse_sendsNamedEvents() throws Exception {
        Router router = new Router();
        router.get("/stream", ctx -> {
            SseEmitter sse = ctx.sse();
            sse.send("message", "payload");
            sse.close();
        });
        TestClient client = TestApplications.client(router);
        TestClient.Response resp = client.get("/stream").execute();
        assertThat(resp.body()).contains("event: message");
        assertThat(resp.body()).contains("data: payload");
    }

    @Test
    void sse_sendsEventWithId() throws Exception {
        Router router = new Router();
        router.get("/stream", ctx -> {
            SseEmitter sse = ctx.sse();
            sse.send("update", "content", "42");
            sse.close();
        });
        TestClient client = TestApplications.client(router);
        TestClient.Response resp = client.get("/stream").execute();
        assertThat(resp.body()).contains("id: 42");
        assertThat(resp.body()).contains("event: update");
        assertThat(resp.body()).contains("data: content");
    }

    // --- abort ---

    @Test
    void abort_inBeforeMiddleware_skipsHandler() {
        Router router = new Router();
        router.before(ctx -> { ctx.status(403); ctx.text("forbidden"); ctx.abort(); });
        router.get("/secret", ctx -> ctx.text("should not reach"));
        TestClient client = TestApplications.client(router);
        TestClient.Response resp = client.get("/secret").execute();
        assertThat(resp.status()).isEqualTo(403);
        assertThat(resp.body()).isEqualTo("forbidden");
    }

    @Test
    void abort_afterHandlersStillRun() {
        Router router = new Router();
        var ran = new boolean[]{false};
        router.before(ctx -> { ctx.status(401); ctx.text("no"); ctx.abort(); });
        router.after(ctx -> ran[0] = true);
        router.get("/x", ctx -> ctx.text("nope"));
        TestClient client = TestApplications.client(router);
        client.get("/x").execute();
        assertThat(ran[0]).isTrue();
    }

    @Test
    void abort_withoutStatus_defaults403() {
        Router router = new Router();
        router.before(ctx -> ctx.abort());
        router.get("/secret", ctx -> ctx.text("should not reach"));
        TestClient client = TestApplications.client(router);
        TestClient.Response resp = client.get("/secret").execute();
        assertThat(resp.status()).isEqualTo(403);
    }

    // --- crud selective ---

    @Test
    void crud_selective_onlyRegistersSpecifiedMethods() {
        Router router = new Router();
        router.crud("/item", new CrudService(), "get", "list");
        TestClient client = TestApplications.client(router);
        assertThat(client.get("/item").execute().status()).isEqualTo(200);
        assertThat(client.get("/item/1").execute().status()).isEqualTo(200);
        assertThat(client.post("/item").body("{}").execute().status()).isEqualTo(404);
        assertThat(client.delete("/item/1").execute().status()).isEqualTo(404);
    }

    @Test
    void crud_invalidMethodName_throws() {
        Router router = new Router();
        assertThatThrownBy(() -> router.crud("/item", new CrudService(), "get", "patch"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("patch");
    }

    static class CrudService {
        public String get(int id) { return "item-" + id; }
        public java.util.List<String> list() { return java.util.List.of("a", "b"); }
        public String create(String body) { return "created"; }
        public void delete(int id) {}
    }
}
