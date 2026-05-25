package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class MethodRefHandlerTest {

    private static final Aura APP = Aura.create();

    // --- service classes with named parameters (compiled with -parameters) ---

    static class BasicSvc {
        public String hello() { return "hi"; }
        public Map<String, Object> getUser(int id) { return Map.of("id", id); }
        public void doNothing(int id) {}
        public String greet(String name) { return "hello " + name; }
    }

    static class TypeSvc {
        public String byInt(int id) { return "int:" + id; }
        public String byLong(long id) { return "long:" + id; }
        public String byDouble(double val) { return "double:" + val; }
        public String byBool(boolean active) { return "bool:" + active; }
        public String byBoxedInt(Integer id) { return "boxed:" + id; }
        public String byBoxedLong(Long id) { return "boxedL:" + id; }
        public String byBoxedDouble(Double val) { return "boxedD:" + val; }
        public String byBoxedBool(Boolean active) { return "boxedB:" + active; }
        public String byString(String name) { return "str:" + name; }
    }

    static class CtxSvc {
        public void withCtx(BaseContext ctx) { ctx.text("got-ctx"); }
    }

    record CreateReq(String name, int age) {}
    static class BodySvc {
        public String create(CreateReq req) { return "created:" + (req != null ? req.name() : "null"); }
    }

    static class ReturnSvc {
        public Map<String, String> data() { return Map.of("k", "v"); }
        public void noReturn(int id) {}
    }

    // --- PLACEHOLDER_MORE_CLASSES ---

    // --- invokeWithArgs tests (existing, expanded) ---

    @Test
    void invokeWithArgs_intParameter_coercesFromString() throws Exception {
        var handler = new MethodRefHandler(new BasicSvc(), "getUser");
        Object result = handler.invokeWithArgs(Map.of("id", "7"));
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map).containsEntry("id", 7);
    }

    @Test
    void invokeWithArgs_intParameter_coercesFromInteger() throws Exception {
        var handler = new MethodRefHandler(new BasicSvc(), "getUser");
        Object result = handler.invokeWithArgs(Map.of("id", 42));
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map).containsEntry("id", 42);
    }

    @Test
    void invokeWithArgs_stringParameter() throws Exception {
        var handler = new MethodRefHandler(new BasicSvc(), "greet");
        assertThat(handler.invokeWithArgs(Map.of("name", "world"))).isEqualTo("hello world");
    }

    @Test
    void invokeWithArgs_noParams() throws Exception {
        var handler = new MethodRefHandler(new BasicSvc(), "hello");
        assertThat(handler.invokeWithArgs(Map.of())).isEqualTo("hi");
    }

    @Test
    void invokeWithArgs_voidMethod_returnsNull() throws Exception {
        var handler = new MethodRefHandler(new BasicSvc(), "doNothing");
        assertThat(handler.invokeWithArgs(Map.of("id", 1))).isNull();
    }

    // --- handle() tests: int ---

    @Test
    void handle_int_fromPath() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byInt");
        var ctx = mockCtx(Map.of("id", "42"), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).contains("\"int:42\"");
    }

    @Test
    void handle_int_fromQuery() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byInt");
        var ctx = mockCtx(Map.of(), Map.of("id", "7"), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).contains("\"int:7\"");
    }

    @Test
    void handle_int_missing_defaultsZero() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byInt");
        var ctx = mockCtx(Map.of(), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).contains("\"int:0\"");
    }

    @Test
    void handle_int_malformed_throwsIllegalArgument() {
        var handler = new MethodRefHandler(new TypeSvc(), "byInt");
        var ctx = mockCtx(Map.of("id", "abc"), Map.of(), null);
        assertThatThrownBy(() -> handler.handle(ctx))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid integer");
    }

    // --- handle() tests: long ---

    @Test
    void handle_long_fromPath() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byLong");
        var ctx = mockCtx(Map.of("id", "999999999999"), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).contains("999999999999");
    }

    @Test
    void handle_long_missing_defaultsZero() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byLong");
        var ctx = mockCtx(Map.of(), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).contains("\"long:0\"");
    }

    @Test
    void handle_long_malformed_throwsIllegalArgument() {
        var handler = new MethodRefHandler(new TypeSvc(), "byLong");
        var ctx = mockCtx(Map.of("id", "xyz"), Map.of(), null);
        assertThatThrownBy(() -> handler.handle(ctx))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid long");
    }

    // --- handle() tests: double ---

    @Test
    void handle_double_fromPath() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byDouble");
        var ctx = mockCtx(Map.of("val", "3.14"), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).contains("3.14");
    }

    @Test
    void handle_double_missing_defaultsZero() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byDouble");
        var ctx = mockCtx(Map.of(), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).contains("\"double:0.0\"");
    }

    @Test
    void handle_double_malformed_throwsIllegalArgument() {
        var handler = new MethodRefHandler(new TypeSvc(), "byDouble");
        var ctx = mockCtx(Map.of("val", "not-a-number"), Map.of(), null);
        assertThatThrownBy(() -> handler.handle(ctx))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid number");
    }

    // --- handle() tests: boolean ---

    @Test
    void handle_bool_true() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byBool");
        var ctx = mockCtx(Map.of(), Map.of("active", "true"), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).contains("\"bool:true\"");
    }

    @Test
    void handle_bool_false() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byBool");
        var ctx = mockCtx(Map.of(), Map.of("active", "false"), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).contains("\"bool:false\"");
    }

    @Test
    void handle_bool_missing_defaultsFalse() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byBool");
        var ctx = mockCtx(Map.of(), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).contains("\"bool:false\"");
    }

    // --- handle() tests: boxed types ---

    @Test
    void handle_boxedInt_present() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byBoxedInt");
        var ctx = mockCtx(Map.of("id", "5"), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).contains("\"boxed:5\"");
    }

    @Test
    void handle_boxedInt_missing_returnsNull() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byBoxedInt");
        var ctx = mockCtx(Map.of(), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).contains("\"boxed:null\"");
    }

    @Test
    void handle_boxedLong_missing_returnsNull() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byBoxedLong");
        var ctx = mockCtx(Map.of(), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).contains("\"boxedL:null\"");
    }

    @Test
    void handle_boxedDouble_present() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byBoxedDouble");
        var ctx = mockCtx(Map.of("val", "2.5"), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).contains("2.5");
    }

    @Test
    void handle_boxedDouble_missing_returnsNull() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byBoxedDouble");
        var ctx = mockCtx(Map.of(), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).contains("\"boxedD:null\"");
    }

    @Test
    void handle_boxedBool_present() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byBoxedBool");
        var ctx = mockCtx(Map.of(), Map.of("active", "true"), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).contains("\"boxedB:true\"");
    }

    @Test
    void handle_boxedBool_missing_returnsNull() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byBoxedBool");
        var ctx = mockCtx(Map.of(), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).contains("\"boxedB:null\"");
    }

    // --- handle() tests: String ---

    @Test
    void handle_string_fromPath() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byString");
        var ctx = mockCtx(Map.of("name", "alice"), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).contains("\"str:alice\"");
    }

    @Test
    void handle_string_missing_returnsNull() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byString");
        var ctx = mockCtx(Map.of(), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).contains("\"str:null\"");
    }

    // --- handle() tests: Context param ---

    @Test
    void handle_contextParam_passesContext() throws Exception {
        var handler = new MethodRefHandler(new CtxSvc(), "withCtx");
        var ctx = mockCtx(Map.of(), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).isEqualTo("got-ctx");
    }

    // --- handle() tests: record body ---

    @Test
    void handle_recordBody_parsed() throws Exception {
        var handler = new MethodRefHandler(new BodySvc(), "create");
        var ctx = mockCtx(Map.of(), Map.of(), "{\"name\":\"tom\",\"age\":25}");
        handler.handle(ctx);
        assertThat(ctx.responseBody).contains("created:tom");
    }

    @Test
    void handle_recordBody_nullBody() throws Exception {
        var handler = new MethodRefHandler(new BodySvc(), "create");
        var ctx = mockCtx(Map.of(), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).contains("created:null");
    }

    // --- handle() tests: return value ---

    @Test
    void handle_returnObject_serializedAsJson() throws Exception {
        var handler = new MethodRefHandler(new ReturnSvc(), "data");
        var ctx = mockCtx(Map.of(), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).contains("\"k\"").contains("\"v\"");
    }

    @Test
    void handle_voidReturn_noAutoResponse() throws Exception {
        var handler = new MethodRefHandler(new ReturnSvc(), "noReturn");
        var ctx = mockCtx(Map.of("id", "1"), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).isNull();
    }

    // --- error cases ---

    @Test
    void methodNotFound_throwsIllegalArgument() {
        assertThatThrownBy(() -> new MethodRefHandler(new BasicSvc(), "nonexistent"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No method 'nonexistent'");
    }

    static class AmbiguousSvc {
        public String process(int id) { return "a"; }
        public String process(String name) { return "b"; }
    }

    @Test
    void ambiguousMethod_throwsIllegalArgument() {
        assertThatThrownBy(() -> new MethodRefHandler(new AmbiguousSvc(), "process"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Ambiguous");
    }

    // --- resolvedMethod / target ---

    @Test
    void resolvedMethod_returnsCorrectMethod() {
        var handler = new MethodRefHandler(new BasicSvc(), "hello");
        assertThat(handler.resolvedMethod().getName()).isEqualTo("hello");
    }

    @Test
    void target_returnsServiceInstance() {
        var svc = new BasicSvc();
        var handler = new MethodRefHandler(svc, "hello");
        assertThat(handler.target()).isSameAs(svc);
    }

    // --- invokeWithArgs coerce paths ---

    @Test
    void invokeWithArgs_longCoerce() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byLong");
        assertThat(handler.invokeWithArgs(Map.of("id", "100"))).isEqualTo("long:100");
    }

    @Test
    void invokeWithArgs_doubleCoerce() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byDouble");
        assertThat(handler.invokeWithArgs(Map.of("val", "1.5"))).isEqualTo("double:1.5");
    }

    @Test
    void invokeWithArgs_boolCoerce() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byBool");
        assertThat(handler.invokeWithArgs(Map.of("active", "true"))).isEqualTo("bool:true");
    }

    @Test
    void invokeWithArgs_nullPrimitive_defaults() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byInt");
        assertThat(handler.invokeWithArgs(Map.of())).isEqualTo("int:0");
    }

    @Test
    void invokeWithArgs_nullLong_defaults() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byLong");
        assertThat(handler.invokeWithArgs(Map.of())).isEqualTo("long:0");
    }

    @Test
    void invokeWithArgs_nullDouble_defaults() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byDouble");
        assertThat(handler.invokeWithArgs(Map.of())).isEqualTo("double:0.0");
    }

    @Test
    void invokeWithArgs_nullBool_defaults() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byBool");
        assertThat(handler.invokeWithArgs(Map.of())).isEqualTo("bool:false");
    }

    @Test
    void invokeWithArgs_boxedNull_returnsNull() throws Exception {
        var handler = new MethodRefHandler(new TypeSvc(), "byBoxedInt");
        assertThat(handler.invokeWithArgs(Map.of())).isEqualTo("boxed:null");
    }

    // --- malformed boxed types ---

    @Test
    void handle_boxedInt_malformed_throwsIllegalArgument() {
        var handler = new MethodRefHandler(new TypeSvc(), "byBoxedInt");
        var ctx = mockCtx(Map.of("id", "abc"), Map.of(), null);
        assertThatThrownBy(() -> handler.handle(ctx))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid integer");
    }

    @Test
    void handle_boxedLong_malformed_throwsIllegalArgument() {
        var handler = new MethodRefHandler(new TypeSvc(), "byBoxedLong");
        var ctx = mockCtx(Map.of("id", "xyz"), Map.of(), null);
        assertThatThrownBy(() -> handler.handle(ctx))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid long");
    }

    @Test
    void handle_boxedDouble_malformed_throwsIllegalArgument() {
        var handler = new MethodRefHandler(new TypeSvc(), "byBoxedDouble");
        var ctx = mockCtx(Map.of("val", "nope"), Map.of(), null);
        assertThatThrownBy(() -> handler.handle(ctx))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid number");
    }

    // --- helper ---

    private MockContext mockCtx(Map<String, String> pathParams, Map<String, String> queryParams, String body) {
        return new MockContext(
            new HashMap<>(pathParams),
            new HashMap<>(queryParams),
            new HashMap<>(),
            body,
            APP
        );
    }
}
