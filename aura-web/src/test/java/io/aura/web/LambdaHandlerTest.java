package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class LambdaHandlerTest {

    @Test
    void noParams_returnsString() throws Exception {
        var handler = new LambdaHandler(new NoParamHandler());
        var ctx = mockCtx(Map.of(), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).isEqualTo("hello");
    }

    @Test
    void pathParam_int() throws Exception {
        var handler = new LambdaHandler(new IntParamHandler());
        var ctx = mockCtx(Map.of("id", "42"), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).isEqualTo("user-42");
    }

    @Test
    void pathParam_int_fallsToQuery() throws Exception {
        var handler = new LambdaHandler(new IntParamHandler());
        var ctx = mockCtx(Map.of(), Map.of("id", "7"), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).isEqualTo("user-7");
    }

    @Test
    void missingParam_intDefaultsToZero() throws Exception {
        var handler = new LambdaHandler(new IntParamHandler());
        var ctx = mockCtx(Map.of(), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).isEqualTo("user-0");
    }

    @Test
    void pathParam_long() throws Exception {
        var handler = new LambdaHandler(new LongParamHandler());
        var ctx = mockCtx(Map.of("id", "999999999999"), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).isEqualTo("item-999999999999");
    }

    @Test
    void missingParam_longDefaultsToZero() throws Exception {
        var handler = new LambdaHandler(new LongParamHandler());
        var ctx = mockCtx(Map.of(), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).isEqualTo("item-0");
    }

    @Test
    void pathParam_string() throws Exception {
        var handler = new LambdaHandler(new StringParamHandler());
        var ctx = mockCtx(Map.of("name", "alice"), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).isEqualTo("hi alice");
    }

    @Test
    void stringParam_fallsToQuery() throws Exception {
        var handler = new LambdaHandler(new StringParamHandler());
        var ctx = mockCtx(Map.of(), Map.of("name", "bob"), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).isEqualTo("hi bob");
    }

    @Test
    void missingParam_stringIsNull() throws Exception {
        var handler = new LambdaHandler(new StringParamHandler());
        var ctx = mockCtx(Map.of(), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).isEqualTo("hi null");
    }

    @Test
    void boxedInteger_missingReturnsNull() throws Exception {
        var handler = new LambdaHandler(new BoxedIntHandler());
        var ctx = mockCtx(Map.of(), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).isEqualTo("val=null");
    }

    @Test
    void boxedInteger_present() throws Exception {
        var handler = new LambdaHandler(new BoxedIntHandler());
        var ctx = mockCtx(Map.of("id", "5"), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).isEqualTo("val=5");
    }

    @Test
    void boxedLong_missingReturnsNull() throws Exception {
        var handler = new LambdaHandler(new BoxedLongHandler());
        var ctx = mockCtx(Map.of(), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).isEqualTo("val=null");
    }

    @Test
    void booleanParam_true() throws Exception {
        var handler = new LambdaHandler(new BoolHandler());
        var ctx = mockCtx(Map.of(), Map.of("active", "true"), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).isEqualTo("active=true");
    }

    @Test
    void booleanParam_missingDefaultsFalse() throws Exception {
        var handler = new LambdaHandler(new BoolHandler());
        var ctx = mockCtx(Map.of(), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).isEqualTo("active=false");
    }

    @Test
    void boxedBoolean_missingReturnsNull() throws Exception {
        var handler = new LambdaHandler(new BoxedBoolHandler());
        var ctx = mockCtx(Map.of(), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).isEqualTo("active=null");
    }

    @Test
    void malformedInt_throwsNumberFormat() throws Exception {
        var handler = new LambdaHandler(new IntParamHandler());
        var ctx = mockCtx(Map.of("id", "abc"), Map.of(), null);
        assertThatThrownBy(() -> handler.handle(ctx))
            .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void recordBody_parsed() throws Exception {
        var handler = new LambdaHandler(new RecordBodyHandler());
        var ctx = mockCtx(Map.of(), Map.of(), "{\"name\":\"tom\",\"age\":25}");
        handler.handle(ctx);
        assertThat(ctx.responseBody).isEqualTo("created:tom");
    }

    @Test
    void nullBody_recordIsNull() throws Exception {
        var handler = new LambdaHandler(new RecordBodyHandler());
        var ctx = mockCtx(Map.of(), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).isEqualTo("created:null");
    }

    @Test
    void returnObject_serializedAsJson() throws Exception {
        var handler = new LambdaHandler(new ObjectReturnHandler());
        var ctx = mockCtx(Map.of(), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).contains("\"k\"").contains("\"v\"");
    }

    @Test
    void voidReturn_noAutoResponse() throws Exception {
        var handler = new LambdaHandler(new VoidHandler());
        var ctx = mockCtx(Map.of(), Map.of(), null);
        handler.handle(ctx);
        assertThat(ctx.responseBody).isEqualTo("manual");
    }

    // --- functional interfaces with named params (compiled with -parameters) ---

    @FunctionalInterface interface NoParam { String apply(); }
    @FunctionalInterface interface IntById { String apply(int id); }
    @FunctionalInterface interface LongById { String apply(long id); }
    @FunctionalInterface interface StrByName { String apply(String name); }
    @FunctionalInterface interface BoxedIntById { String apply(Integer id); }
    @FunctionalInterface interface BoxedLongById { String apply(Long id); }
    @FunctionalInterface interface BoolByActive { String apply(boolean active); }
    @FunctionalInterface interface BoxedBoolByActive { String apply(Boolean active); }
    @FunctionalInterface interface RecordBody { String apply(CreateReq req); }
    @FunctionalInterface interface ObjReturn { Map<String, String> apply(); }
    @FunctionalInterface interface VoidCtx { void apply(BaseContext ctx); }

    record CreateReq(String name, int age) {}

    // concrete classes so parameter names are reliably preserved
    static class NoParamHandler implements NoParam {
        public String apply() { return "hello"; }
    }
    static class IntParamHandler implements IntById {
        public String apply(int id) { return "user-" + id; }
    }
    static class LongParamHandler implements LongById {
        public String apply(long id) { return "item-" + id; }
    }
    static class StringParamHandler implements StrByName {
        public String apply(String name) { return "hi " + name; }
    }
    static class BoxedIntHandler implements BoxedIntById {
        public String apply(Integer id) { return "val=" + id; }
    }
    static class BoxedLongHandler implements BoxedLongById {
        public String apply(Long id) { return "val=" + id; }
    }
    static class BoolHandler implements BoolByActive {
        public String apply(boolean active) { return "active=" + active; }
    }
    static class BoxedBoolHandler implements BoxedBoolByActive {
        public String apply(Boolean active) { return "active=" + active; }
    }
    static class RecordBodyHandler implements RecordBody {
        public String apply(CreateReq req) { return "created:" + (req != null ? req.name() : null); }
    }
    static class ObjectReturnHandler implements ObjReturn {
        public Map<String, String> apply() { return Map.of("k", "v"); }
    }
    static class VoidHandler implements VoidCtx {
        public void apply(BaseContext ctx) { ctx.text("manual"); }
    }

    private MockContext mockCtx(Map<String, String> pathParams, Map<String, String> queryParams, String body) {
        return new MockContext(
            new HashMap<>(pathParams),
            new HashMap<>(queryParams),
            new HashMap<>(),
            body,
            Aura.create()
        );
    }
}