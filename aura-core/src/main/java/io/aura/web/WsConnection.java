package io.aura.web;

public interface WsConnection {

    void send(String message);

    boolean isOpen();

    void close();

    void close(int code, String reason);

    String path(String name);

    String query(String name);

    void set(String key, Object value);

    <T> T get(String key, Class<T> type);
}
