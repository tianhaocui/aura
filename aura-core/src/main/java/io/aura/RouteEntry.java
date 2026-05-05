package io.aura;

import java.util.function.Supplier;

public record RouteEntry(String method, String path, Object handler, String description) {

    @FunctionalInterface public interface F0<R> { R apply(); }
    @FunctionalInterface public interface F1<A, R> { R apply(A a); }
    @FunctionalInterface public interface F2<A, B, R> { R apply(A a, B b); }
}
