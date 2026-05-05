package io.aura;

public interface JsonEngine {
    String toJson(Object obj);
    <T> T fromJson(String json, Class<T> type);
}
