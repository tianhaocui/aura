package io.aura;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ResultTest {

    @Test
    void ok_withData() {
        Result<String> result = Result.ok("hello");
        assertThat(result.code()).isEqualTo(0);
        assertThat(result.message()).isEqualTo("ok");
        assertThat(result.data()).isEqualTo("hello");
    }

    @Test
    void ok_withMessageAndData() {
        Result<Integer> result = Result.ok("created", 42);
        assertThat(result.code()).isEqualTo(0);
        assertThat(result.message()).isEqualTo("created");
        assertThat(result.data()).isEqualTo(42);
    }

    @Test
    void fail_withCodeAndMessage() {
        Result<?> result = Result.fail(404, "not found");
        assertThat(result.code()).isEqualTo(404);
        assertThat(result.message()).isEqualTo("not found");
        assertThat(result.data()).isNull();
    }

    @Test
    void ok_withNullData() {
        Result<Object> result = Result.ok(null);
        assertThat(result.code()).isEqualTo(0);
        assertThat(result.data()).isNull();
    }
}
