package io.aura.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;
import java.util.Objects;
import java.util.function.Consumer;

/** Minimal JSON structured-output parsing and validation helpers. */
public final class StructuredOutput {
    private StructuredOutput() { }

    public static <T> T parse(AiResponse response, Class<T> type) {
        Objects.requireNonNull(response, "response");
        return parse(response.content(), type);
    }

    public static <T> T parse(String json, Class<T> type) {
        Objects.requireNonNull(type, "type");
        if (json == null || json.isBlank()) throw new IllegalArgumentException("structured output is blank");
        try {
            return JSON.parseObject(json, type, JSONReader.Feature.ErrorOnNotSupportAutoType);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("invalid structured output JSON", error);
        }
    }

    public static <T> T parseAndValidate(AiResponse response, Class<T> type, Consumer<? super T> validator) {
        T value = parse(response, type);
        Objects.requireNonNull(validator, "validator").accept(value);
        return value;
    }

    public static void validateJson(String json) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("structured output is blank");
        try {
            JSON.parse(json);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("invalid structured output JSON", error);
        }
    }
}
