package io.aura.snapshot;

import java.util.Optional;
import java.util.concurrent.Callable;

public final class Snapshot {

    private static final ThreadLocal<SnapshotScope> CURRENT = new ThreadLocal<>();

    private Snapshot() {}

    public static Optional<SnapshotScope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static SnapshotScope currentOrNull() {
        return CURRENT.get();
    }

    public static void bind(SnapshotScope scope) {
        CURRENT.set(scope);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static void put(String key, Object value) {
        SnapshotScope scope = CURRENT.get();
        if (scope != null) scope.put(key, value);
    }

    public static void response(int status, String contentType, Object body) {
        SnapshotScope scope = CURRENT.get();
        if (scope != null) scope.response(status, contentType, body);
    }

    public static void externalCall(ExceptionSnapshot.ExternalCallSnapshot call) {
        SnapshotScope scope = CURRENT.get();
        if (scope != null) scope.externalCall(call);
    }

    public static <T> T withScope(SnapshotScope scope, Callable<T> action) throws Exception {
        SnapshotScope previous = CURRENT.get();
        CURRENT.set(scope);
        try {
            return action.call();
        } finally {
            if (previous != null) CURRENT.set(previous);
            else CURRENT.remove();
        }
    }
}
