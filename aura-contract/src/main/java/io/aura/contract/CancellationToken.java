package io.aura.contract;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative cancellation signal shared by adapters and module implementations. */
public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public boolean isCancelled() {
        return cancelled.get();
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
