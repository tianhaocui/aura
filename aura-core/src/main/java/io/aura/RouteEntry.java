package io.aura;

public record RouteEntry(String method, String path, Object handler, String description) {

    @FunctionalInterface public interface F0<R> { R apply(); }
}
