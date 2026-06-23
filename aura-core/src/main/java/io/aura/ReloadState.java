package io.aura;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

class ReloadState {

    static volatile Aura RELOAD_INSTANCE;
    static volatile boolean devMode;
    static final List<Runnable> cleanupHooks = new CopyOnWriteArrayList<>();

    static void fireCleanup() {
        for (Runnable hook : cleanupHooks) {
            try { hook.run(); } catch (Exception ignored) {}
        }
    }
}
