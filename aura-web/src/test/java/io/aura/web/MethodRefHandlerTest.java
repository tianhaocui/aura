package io.aura.web;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class MethodRefHandlerTest {

    static class TestService {
        public String hello() { return "hi"; }
        public Map<String, Object> getUser(int id) { return Map.of("id", id); }
        public void doNothing(int id) {}
        public String greet(String name) { return "hello " + name; }
    }

    @Test
    void invokeWithArgs_intParameter_coercesFromString() throws Exception {
        TestService svc = new TestService();
        MethodRefHandler handler = new MethodRefHandler(svc, "getUser");
        Object result = handler.invokeWithArgs(Map.of("id", "7"));
        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map).containsEntry("id", 7);
    }

    @Test
    void invokeWithArgs_intParameter_coercesFromInteger() throws Exception {
        TestService svc = new TestService();
        MethodRefHandler handler = new MethodRefHandler(svc, "getUser");
        Object result = handler.invokeWithArgs(Map.of("id", 42));
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map).containsEntry("id", 42);
    }

    @Test
    void invokeWithArgs_stringParameter_passesThrough() throws Exception {
        TestService svc = new TestService();
        MethodRefHandler handler = new MethodRefHandler(svc, "greet");
        Object result = handler.invokeWithArgs(Map.of("name", "world"));
        assertThat(result).isEqualTo("hello world");
    }

    @Test
    void invokeWithArgs_returnValueIsCorrect() throws Exception {
        TestService svc = new TestService();
        MethodRefHandler handler = new MethodRefHandler(svc, "hello");
        Object result = handler.invokeWithArgs(Map.of());
        assertThat(result).isEqualTo("hi");
    }

    @Test
    void invokeWithArgs_voidMethod_returnsNull() throws Exception {
        TestService svc = new TestService();
        MethodRefHandler handler = new MethodRefHandler(svc, "doNothing");
        Object result = handler.invokeWithArgs(Map.of("id", 1));
        assertThat(result).isNull();
    }
}
