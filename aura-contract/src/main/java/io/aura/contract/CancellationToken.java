package io.aura.contract;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative cancellation signal shared by adapters and module implementations. */
public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CancellationToken[] parents;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Registration> parentRegistrations = new CopyOnWriteArrayList<>();

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override void close();
    }

    public CancellationToken() {
        this.parents = new CancellationToken[0];
    }

    private CancellationToken(CancellationToken[] parents) {
        this.parents = parents;
        for (CancellationToken parent : parents) {
            if (isCancelled()) break;
            Registration registration = parent.onCancel(this::cancel);
            parentRegistrations.add(registration);
            if (isCancelled()) {
                registration.close();
                break;
            }
        }
    }

    /**
     * Creates a token that is cancelled when this token is cancelled. Cancelling
     * the child never changes the parent.
     */
    public CancellationToken child() {
        return linked(this);
    }

    /**
     * Creates a token that observes all supplied parents. This is useful at a
     * boundary where both a caller and a runtime shutdown signal must stop the
     * same operation. The returned token owns its local cancellation state.
     */
    public static CancellationToken linked(CancellationToken... parents) {
        if (parents == null || parents.length == 0) return new CancellationToken();
        CancellationToken[] copy = parents.clone();
        for (CancellationToken parent : copy) Objects.requireNonNull(parent, "parent");
        return new CancellationToken(copy);
    }

    public boolean isCancelled() {
        if (cancelled.get()) return true;
        for (CancellationToken parent : parents) {
            if (parent.isCancelled()) return true;
        }
        return false;
    }

    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) return false;
        for (Runnable listener : listeners) listener.run();
        listeners.clear();
        for (Registration registration : parentRegistrations) registration.close();
        parentRegistrations.clear();
        return true;
    }

    /** Registers a best-effort callback invoked once when this token is cancelled. */
    public Registration onCancel(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        AtomicBoolean active = new AtomicBoolean(true);
        Runnable guarded = () -> {
            if (active.compareAndSet(true, false)) {
                try {
                    listener.run();
                } catch (RuntimeException ignored) {
                    // Cancellation must notify other listeners even if one callback fails.
                }
            }
        };
        if (isCancelled()) {
            guarded.run();
            return () -> { };
        }
        listeners.add(guarded);
        if (isCancelled() && listeners.remove(guarded)) guarded.run();
        return () -> {
            if (active.compareAndSet(true, false)) listeners.remove(guarded);
        };
    }

    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancellationException("Operation cancelled");
        }
    }
}
