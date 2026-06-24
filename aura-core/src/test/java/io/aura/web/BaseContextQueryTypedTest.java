package io.aura.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class BaseContextQueryTypedTest {

    private BaseContext contextWith(String name, String value) {
        return new BaseContext() {
            @Override public String path(String n) { return null; }
            @Override public String query(String n) { return n.equals(name) ? value : null; }
            @Override public String query(String n, String def) { String v = query(n); return v == null ? def : v; }
            @Override public String header(String n) { return null; }
            @Override public String cookie(String n) { return null; }
            @Override public <T> T body(Class<T> type) { return null; }
            @Override public String method() { return "GET"; }
            @Override public String url() { return "/"; }
            @Override public int statusCode() { return 200; }
            @Override public BaseContext status(int code) { return this; }
            @Override public void json(Object obj) {}
            @Override public void text(String text) {}
            @Override public void html(String html) {}
            @Override public void raw(String body) {}
            @Override public void redirect(String url) {}
            @Override public BaseContext header(String n, String v) { return this; }
            @Override public BaseContext cookie(String n, String v, int maxAge) { return this; }
            @Override public <T> void set(T instance) {}
            @Override public <T> T get(Class<T> type) { return null; }
            @Override public void set(String key, Object v) {}
            @Override public <T> T get(String key, Class<T> type) { return null; }
            @Override public void abort() {}
            @Override public boolean isAborted() { return false; }
        };
    }

    @Test
    void queryInt_validValue() {
        assertThat(contextWith("page", "3").queryInt("page", 1)).isEqualTo(3);
    }

    @Test
    void queryInt_null_returnsDefault() {
        assertThat(contextWith("page", null).queryInt("page", 1)).isEqualTo(1);
    }

    @Test
    void queryInt_invalid_returnsDefault() {
        assertThat(contextWith("page", "abc").queryInt("page", 1)).isEqualTo(1);
    }

    @Test
    void queryInt_blank_returnsDefault() {
        assertThat(contextWith("page", "  ").queryInt("page", 1)).isEqualTo(1);
    }

    @Test
    void queryLong_validValue() {
        assertThat(contextWith("since", "1717200000000").queryLong("since", 0L)).isEqualTo(1717200000000L);
    }

    @Test
    void queryLong_null_returnsDefault() {
        assertThat(contextWith("since", null).queryLong("since", 0L)).isEqualTo(0L);
    }

    @Test
    void queryLong_invalid_returnsDefault() {
        assertThat(contextWith("since", "xyz").queryLong("since", 0L)).isEqualTo(0L);
    }

    @Test
    void queryBool_true() {
        assertThat(contextWith("active", "true").queryBool("active", false)).isTrue();
    }

    @Test
    void queryBool_TRUE_caseInsensitive() {
        assertThat(contextWith("active", "TRUE").queryBool("active", false)).isTrue();
    }

    @Test
    void queryBool_false() {
        assertThat(contextWith("active", "false").queryBool("active", true)).isFalse();
    }

    @Test
    void queryBool_null_returnsDefault() {
        assertThat(contextWith("active", null).queryBool("active", true)).isTrue();
    }

    @Test
    void queryBool_nonsense_returnsFalse() {
        assertThat(contextWith("active", "yes").queryBool("active", false)).isFalse();
    }
}
