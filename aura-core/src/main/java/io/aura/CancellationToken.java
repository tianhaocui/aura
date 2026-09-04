package io.aura;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative request cancellation signal. Handlers should check this token at safe points. */
public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public boolean isCancelled() { return cancelled.get(); }
    public boolean cancel() { return cancelled.compareAndSet(false, true); }
    public void throwIfCancelled() {
        if (isCancelled()) throw new CancellationException("Request cancelled");
    }
}
