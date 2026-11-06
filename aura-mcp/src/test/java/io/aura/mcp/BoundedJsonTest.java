package io.aura.mcp;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedJsonTest {

    record Result(String name, int count) { }

    @Test
    void writesValidUtf8JsonForProtocolValueTypes() {
        String json = BoundedJson.encode(Map.of(
                "text", "hello\nworld",
                "record", new Result("ok", 2),
                "values", java.util.Arrays.asList(true, 3, null)), 4096);

        assertThat(JSON.isValid(json)).isTrue();
        assertThat(json).contains("hello\\nworld", "\"name\":\"ok\"");
    }

    @Test
    void stopsGeneratingIterableAsSoonAsByteBudgetIsExceeded() {
        AtomicInteger generated = new AtomicInteger();
        Iterable<String> values = () -> new Iterator<>() {
            @Override public boolean hasNext() { return generated.get() < 1_000_000; }
            @Override public String next() {
                generated.incrementAndGet();
                return "x".repeat(1024);
            }
        };

        assertThatThrownBy(() -> BoundedJson.encode(Map.of("values", values), 4096))
                .isInstanceOf(BoundedJson.LimitExceededException.class);
        assertThat(generated.get()).isLessThan(20);
    }

    @Test
    void rejectsCyclesAndExcessiveNesting() {
        java.util.List<Object> cycle = new java.util.ArrayList<>();
        cycle.add(cycle);

        assertThatThrownBy(() -> BoundedJson.encode(cycle, 4096))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cyclic");
    }
}
