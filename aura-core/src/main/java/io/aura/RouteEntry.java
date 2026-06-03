package io.aura;

public record RouteEntry(String method, String path, Object handler, String description) {
}
