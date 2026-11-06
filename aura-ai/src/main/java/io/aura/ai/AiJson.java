package io.aura.ai;

import com.alibaba.fastjson2.JSON;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON-compatible immutable snapshots for provider-neutral request values. */
final class AiJson {
    private AiJson() { }

    @SuppressWarnings("unchecked")
    static Map<String, Object> immutableMap(Map<String, ?> value, String name) {
        if (value == null || value.isEmpty()) return Map.of();
        try {
            Object parsed = JSON.parse(JSON.toJSONString(value));
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException(name + " must be a JSON object");
            }
            return (Map<String, Object>) freeze(map);
        } catch (RuntimeException | StackOverflowError error) {
            throw new IllegalArgumentException(name + " must contain finite JSON-compatible values", error);
        }
    }

    static int length(Object value) {
        return stringify(value).length();
    }

    static String stringify(Object value) {
        try {
            return JSON.toJSONString(value);
        } catch (RuntimeException | StackOverflowError error) {
            throw new IllegalArgumentException("value must contain finite JSON-compatible data", error);
        }
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(String.valueOf(key), freeze(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof java.util.Collection<?> collection) {
            List<Object> copy = new ArrayList<>(collection.size());
            collection.forEach(item -> copy.add(freeze(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
