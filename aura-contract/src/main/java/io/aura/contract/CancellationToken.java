package io.aura.contract;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative cancellation signal shared by adapters and module implementations. */
public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CancellationToken[] parents;

    public CancellationToken() {
        this.parents = new CancellationToken[0];
    }

    private CancellationToken(CancellationToken[] parents) {
        this.parents = parents;
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
        return cancelled.compareAndSet(false, true);
    }

    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancellationException("Operation cancelled");
        }
    }
}
