package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for Context / MockContext behavior not already covered by TestClientTest.
 *
 * Covered here:
 *  - set(T) / get(Class<T>) type-based attribute storage
 *  - set(T) / get(Class<T>) with subtype (isAssignableFrom)
 *  - set(String, Object) / get(String, Class<T>) named attribute storage
 *  - status(int) chaining
 *  - json(Object) serialization format
 *  - body(Class<T>) with null / empty body
 *  - redirect() with lone \n (not just \r\n)
 *  - query(name, default) when value is present
 *  - statusCode() default (0 → 200 via MockContext)
 *  - method() / url() on MockContext return empty strings
 *  - cookie(String) on MockContext returns null
 *  - SSE sanitize strips CRLF from event names and ids
 *  - SSE multiline data splits into multiple data: lines
 *  - Context.redirect() CRLF guard — \r alone, \n alone, \r\n combined
 */
class ContextTest {

    private static Aura app() { return Aura.create(); }

    private static MockContext mockCtx() {
        return new MockContext(Map.of(), Map.of(), Map.of(), null, app());
    }

    private static MockContext mockCtxWithQuery(Map<String, String> query) {
        return new MockContext(Map.of(), query, Map.of(), null, app());
    }

    private static MockContext mockCtxWithBody(String body) {
        return new MockContext(Map.of(), Map.of(), Map.of(), body, app());
    }

    // -------------------------------------------------------------------------
    // set(T) / get(Class<T>) — type-based attribute storage
    // -------------------------------------------------------------------------

    @Test
    void set_and_get_byType_roundtrips() {
        MockContext ctx = mockCtx();
        ctx.set("hello");
        assertThat(ctx.get(String.class)).isEqualTo("hello");
    }

    @Test
    void get_byType_returnsNull_whenNotSet() {
        MockContext ctx = mockCtx();
        assertThat(ctx.get(String.class)).isNull();
    }

    @Test
    void set_and_get_byType_withCustomObject() {
        MockContext ctx = mockCtx();
        var user = new User("alice", 30);
        ctx.set(user);
        assertThat(ctx.get(User.class)).isSameAs(user);
    }

    @Test
    void get_byType_withSupertype_returnsSubtypeInstance() {
        MockContext ctx = mockCtx();
        var impl = new ConcreteService();
        ctx.set(impl);
        // isAssignableFrom: AbstractService.isAssignableFrom(ConcreteService) → true
        assertThat(ctx.get(AbstractService.class)).isSameAs(impl);
    }

    @Test
    void set_overwritesSameType() {
        MockContext ctx = mockCtx();
        ctx.set("first");
        ctx.set("second");
        assertThat(ctx.get(String.class)).isEqualTo("second");
    }

    // -------------------------------------------------------------------------
    // set(String, Object) / get(String, Class<T>) — named attribute storage
    // -------------------------------------------------------------------------

    @Test
    void set_and_get_byName_roundtrips() {
        MockContext ctx = mockCtx();
        ctx.set("userId", 42);
        assertThat(ctx.get("userId", Integer.class)).isEqualTo(42);
    }

    @Test
    void get_byName_returnsNull_whenKeyAbsent() {
        MockContext ctx = mockCtx();
        assertThat(ctx.get("missing", String.class)).isNull();
    }

    @Test
    void set_byName_overwritesPreviousValue() {
        MockContext ctx = mockCtx();
        ctx.set("key", "v1");
        ctx.set("key", "v2");
        assertThat(ctx.get("key", String.class)).isEqualTo("v2");
    }

    @Test
    void namedAndTyped_attrs_areIndependent() {
        MockContext ctx = mockCtx();
        ctx.set("label", "named-value");
        ctx.set("typed-string");  // type-keyed
        // named lookup should not see the type-keyed value
        assertThat(ctx.get("label", String.class)).isEqualTo("named-value");
        assertThat(ctx.get(String.class)).isEqualTo("typed-string");
    }

    // -------------------------------------------------------------------------
    // status(int) chaining
    // -------------------------------------------------------------------------

    @Test
    void status_returnsContextForChaining() {
        MockContext ctx = mockCtx();
        Context returned = ctx.status(201);
        assertThat(returned).isSameAs(ctx);
        assertThat(ctx.statusCode()).isEqualTo(201);
    }

    // -------------------------------------------------------------------------
    // statusCode() default
    // -------------------------------------------------------------------------

    @Test
    void statusCode_defaultsTo200_whenNotSet() {
        MockContext ctx = mockCtx();
        assertThat(ctx.statusCode()).isEqualTo(200);
    }

    // -------------------------------------------------------------------------
    // method() / url() on MockContext
    // -------------------------------------------------------------------------

    @Test
    void method_returnsEmptyString() {
        assertThat(mockCtx().method()).isEqualTo("");
    }

    @Test
    void url_returnsEmptyString() {
        assertThat(mockCtx().url()).isEqualTo("");
    }

    // -------------------------------------------------------------------------
    // cookie(String) on MockContext
    // -------------------------------------------------------------------------

    @Test
    void cookie_returnsNull_onMockContext() {
        assertThat(mockCtx().cookie("session")).isNull();
    }

    // -------------------------------------------------------------------------
    // query(name, default) when value IS present
    // -------------------------------------------------------------------------

    @Test
    void query_withDefault_returnsActualValue_whenPresent() {
        MockContext ctx = mockCtxWithQuery(Map.of("page", "3"));
        assertThat(ctx.query("page", "1")).isEqualTo("3");
    }

    @Test
    void query_withDefault_returnsDefault_whenAbsent() {
        MockContext ctx = mockCtxWithQuery(Map.of());
        assertThat(ctx.query("page", "1")).isEqualTo("1");
    }

    // -------------------------------------------------------------------------
    // json(Object) serialization respects JsonConfig
    // -------------------------------------------------------------------------

    @Test
    void json_writesNulls_whenJsonConfigEnabled() {
        Aura a = Aura.create().jsonConfig(cfg -> cfg.writeNulls(true));
        MockContext ctx = new MockContext(Map.of(), Map.of(), Map.of(), null, a);
        ctx.json(new NullableObj("hello", null));
        assertThat(ctx.responseBody).contains("\"name\"");
        assertThat(ctx.responseBody).contains("\"hello\"");
        assertThat(ctx.responseBody).contains("\"value\":null");
    }

    @Test
    void json_omitsNulls_whenWriteNullsDisabled() {
        Aura a = Aura.create(); // writeNulls defaults to false
        MockContext ctx = new MockContext(Map.of(), Map.of(), Map.of(), null, a);
        ctx.json(new NullableObj("hello", null));
        assertThat(ctx.responseBody).contains("\"name\"");
        assertThat(ctx.responseBody).doesNotContain("\"value\"");
    }

    @Test
    void json_respectsCustomDateFormat_viaTestClient() {
        Aura a = Aura.create().jsonConfig(cfg -> cfg.dateFormat("yyyy-MM-dd"));
        Router router = new Router();
        router.get("/date", ctx -> ctx.json(Map.of("d", java.time.LocalDate.of(2025, 1, 15))));
        TestClient client = new TestClient(a, router);
        String body = client.get("/date").execute().body();
        assertThat(body).contains("2025-01-15");
    }

    // -------------------------------------------------------------------------
    // json(Object) serialization format
    // -------------------------------------------------------------------------

    @Test
    void json_serializes_toJsonString() {
        MockContext ctx = mockCtx();
        ctx.json(Map.of("key", "value"));
        assertThat(ctx.responseBody).contains("\"key\"");
        assertThat(ctx.responseBody).contains("\"value\"");
    }

    @Test
    void json_serializes_mapWithMultipleEntries() {
        MockContext ctx = mockCtx();
        ctx.json(Map.of("status", "ok", "code", 0));
        assertThat(ctx.responseBody).contains("\"status\"");
        assertThat(ctx.responseBody).contains("\"ok\"");
        assertThat(ctx.responseBody).contains("\"code\"");
    }

    // -------------------------------------------------------------------------
    // body(Class<T>) with null / empty / blank body
    // -------------------------------------------------------------------------

    @Test
    void body_returnsNull_whenBodyIsNull() {
        MockContext ctx = mockCtxWithBody(null);
        assertThat(ctx.body(String.class)).isNull();
    }

    @Test
    void body_returnsNull_whenBodyIsEmpty() {
        MockContext ctx = mockCtxWithBody("");
        assertThat(ctx.body(String.class)).isNull();
    }

    @Test
    void body_returnsNull_whenBodyIsWhitespaceOnly() {
        MockContext ctx = mockCtxWithBody("   \t\n  ");
        assertThat(ctx.body(User.class)).isNull();
    }

    @Test
    void body_parsesEmptyJson_toObjectWithDefaults() {
        MockContext ctx = mockCtxWithBody("{}");
        User user = ctx.body(User.class);
        assertThat(user).isNotNull();
        assertThat(user.name()).isNull();
        assertThat(user.age()).isEqualTo(0);
    }

    @Test
    void body_deserializesJson_toTypedObject() {
        MockContext ctx = mockCtxWithBody("{\"name\":\"bob\",\"age\":25}");
        User user = ctx.body(User.class);
        assertThat(user).isNotNull();
        assertThat(user.name()).isEqualTo("bob");
        assertThat(user.age()).isEqualTo(25);
    }

    // -------------------------------------------------------------------------
    // Context.redirect() CRLF guard — via TestClient (exercises real Context guard)
    // -------------------------------------------------------------------------

    @Test
    void redirect_withCarriageReturn_throwsIllegalArgument() {
        Context ctx = new Context(null, Map.of(), app(), null);
        assertThatThrownBy(() -> ctx.redirect("/path\rX-Evil: injected"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid redirect URL");
    }

    @Test
    void redirect_withNewline_throwsIllegalArgument() {
        Context ctx = new Context(null, Map.of(), app(), null);
        assertThatThrownBy(() -> ctx.redirect("/path\nX-Evil: injected"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid redirect URL");
    }

    @Test
    void redirect_withCrLf_throwsIllegalArgument() {
        Context ctx = new Context(null, Map.of(), app(), null);
        assertThatThrownBy(() -> ctx.redirect("/path\r\nX-Evil: injected"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid redirect URL");
    }

    @Test
    void redirect_withCleanUrl_doesNotThrow_onMockContext() {
        // MockContext.redirect doesn't call the real guard, but verifies the mock sets 302
        MockContext ctx = mockCtx();
        ctx.redirect("/safe-path");
        assertThat(ctx.statusCode()).isEqualTo(302);
    }

    // -------------------------------------------------------------------------
    // SSE — sanitize strips CRLF from event names and ids
    // -------------------------------------------------------------------------

    @Test
    void sse_sanitizesEventName_stripsCrlf() throws Exception {
        MockContext ctx = mockCtx();
        SseEmitter sse = ctx.sse();
        sse.send("evil\r\nevent", "data");
        assertThat(ctx.responseBody).doesNotContain("\r");
        assertThat(ctx.responseBody).doesNotContain("\nevent");
        assertThat(ctx.responseBody).contains("event: evilevent");
    }

    @Test
    void sse_sanitizesId_stripsCrlf() throws Exception {
        MockContext ctx = mockCtx();
        SseEmitter sse = ctx.sse();
        sse.send("msg", "payload", "id\r\nX-Injected: evil");
        assertThat(ctx.responseBody).contains("id: idX-Injected: evil");
        assertThat(ctx.responseBody).doesNotContain("id: id\r\n");
    }

    // -------------------------------------------------------------------------
    // SSE — multiline data splits into multiple data: lines
    // -------------------------------------------------------------------------

    @Test
    void sse_multilineData_emitsMultipleDataLines() throws Exception {
        MockContext ctx = mockCtx();
        SseEmitter sse = ctx.sse();
        sse.send("line1\nline2\nline3");
        assertThat(ctx.responseBody).contains("data: line1\n");
        assertThat(ctx.responseBody).contains("data: line2\n");
        assertThat(ctx.responseBody).contains("data: line3\n");
    }

    @Test
    void sse_singleLine_emitsOneDataLine() throws Exception {
        MockContext ctx = mockCtx();
        SseEmitter sse = ctx.sse();
        sse.send("hello");
        assertThat(ctx.responseBody).isEqualTo("data: hello\n\n");
    }

    @Test
    void sse_namedEvent_formatsCorrectly() throws Exception {
        MockContext ctx = mockCtx();
        SseEmitter sse = ctx.sse();
        sse.send("update", "content");
        assertThat(ctx.responseBody).startsWith("event: update\n");
        assertThat(ctx.responseBody).contains("data: content\n");
    }

    @Test
    void sse_eventWithId_formatsCorrectly() throws Exception {
        MockContext ctx = mockCtx();
        SseEmitter sse = ctx.sse();
        sse.send("ping", "pong", "99");
        assertThat(ctx.responseBody).startsWith("id: 99\n");
        assertThat(ctx.responseBody).contains("event: ping\n");
        assertThat(ctx.responseBody).contains("data: pong\n");
    }

    @Test
    void sse_multipleMessages_appendCorrectly() throws Exception {
        MockContext ctx = mockCtx();
        SseEmitter sse = ctx.sse();
        sse.send("first");
        sse.send("second");
        assertThat(ctx.responseBody).contains("data: first\n");
        assertThat(ctx.responseBody).contains("data: second\n");
    }

    // -------------------------------------------------------------------------
    // TestClient integration — set/get attrs survive through route handler
    // -------------------------------------------------------------------------

    @Test
    void set_and_get_byType_worksInRouteHandler() {
        Aura a = app();
        Router router = new Router();
        router.before(ctx -> ctx.set(new User("carol", 20)));
        router.get("/me", ctx -> {
            User u = ctx.get(User.class);
            ctx.text(u != null ? u.name() : "null");
        });
        TestClient client = new TestClient(a, router);
        assertThat(client.get("/me").execute().body()).isEqualTo("carol");
    }

    @Test
    void set_and_get_byName_worksInRouteHandler() {
        Aura a = app();
        Router router = new Router();
        router.before(ctx -> ctx.set("requestId", "req-123"));
        router.get("/id", ctx -> ctx.text(ctx.get("requestId", String.class)));
        TestClient client = new TestClient(a, router);
        assertThat(client.get("/id").execute().body()).isEqualTo("req-123");
    }

    // -------------------------------------------------------------------------
    // Helper types
    // -------------------------------------------------------------------------

    record User(String name, int age) {}
        // -------------------------------------------------------------------------
    // queryMap() — returns all query params as Map<String, String>

    @Test void queryMap_returnsAllParams() {
        MockContext ctx = mockCtxWithQuery(Map.of("a", "1", "b", "2"));
        assertThat(ctx.queryMap()).containsEntry("a", "1").containsEntry("b", "2").hasSize(2);
    }

    @Test void queryMap_emptyWhenNoParams() {
        MockContext ctx = mockCtx();
        assertThat(ctx.queryMap()).isEmpty();
    }

    record NullableObj(String name, String value) {}

    static abstract class AbstractService {}
    static class ConcreteService extends AbstractService {}
}
