package io.aura;

import io.aura.contract.ModuleMetadata;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Owns deterministic module registration and lifecycle. */
final class ModuleManager {
    private final List<AuraModule> modules = new CopyOnWriteArrayList<>();
    private final List<ModuleMetadata> installedDescriptors = new CopyOnWriteArrayList<>();
    private final List<AuraModule> installedModules = new CopyOnWriteArrayList<>();
    private final List<AuraModule> startedModules = new CopyOnWriteArrayList<>();
    private volatile boolean installed;
    private volatile boolean started;

    void add(AuraModule module) {
        if (module == null) throw new IllegalArgumentException("module must not be null");
        if (installed) throw new IllegalStateException("Modules cannot be added after module preparation");
        ModuleMetadata descriptor = module.descriptor();
        if (descriptor == null) {
            throw new ModuleException("AURA_MODULE_DESCRIPTOR_MISSING", "?",
                    "Module descriptor must not be null");
        }
        if (modules.stream().anyMatch(existing -> existing.descriptor().id().equals(descriptor.id()))) {
            throw new ModuleException("AURA_MODULE_DUPLICATE", descriptor.id(),
                    "Duplicate module id: " + descriptor.id());
        }
        modules.add(module);
    }

    List<AuraModule> modules() { return List.copyOf(modules); }

    List<ModuleMetadata> descriptors() {
        if (!installedDescriptors.isEmpty()) return List.copyOf(installedDescriptors);
        return modules.stream().map(AuraModule::descriptor).toList();
    }

    boolean installed() { return installed; }
    boolean started() { return started; }

    synchronized void install(Function<AuraModule, ModuleContext> contexts, boolean startModules) {
        if (installed) {
            if (startModules && !started) start(contexts);
            return;
        }
        List<AuraModule> ordered = ModuleGraph.order(modules);
        List<AuraModule> installing = new java.util.ArrayList<>();
        try {
            for (AuraModule module : ordered) {
                ModuleMetadata descriptor = module.descriptor();
                installing.add(module);
                module.install(contexts.apply(module));
                installedDescriptors.add(descriptor);
                installedModules.add(module);
            }
        } catch (Throwable failure) {
            stopModules(installing, contexts, failure);
            installedDescriptors.clear();
            installedModules.clear();
            if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            if (failure instanceof Error errorFailure) throw errorFailure;
            throw new RuntimeException(failure);
        }
        modules.clear();
        modules.addAll(ordered);
        installed = true;
        if (startModules) start(contexts);
    }

    synchronized void start(Function<AuraModule, ModuleContext> contexts) {
        if (started) return;
        for (AuraModule module : modules) {
            try {
                module.start(contexts.apply(module));
                startedModules.add(module);
            } catch (Throwable error) {
                try {
                    module.stop(contexts.apply(module));
                } catch (Throwable cleanupFailure) {
                    error.addSuppressed(cleanupFailure);
                }
                stopStarted(contexts, error);
                installedModules.clear();
                installedDescriptors.clear();
                throw new ModuleException("AURA_MODULE_START_FAILED", module.descriptor().id(),
                        "Failed to start module " + module.descriptor().id() + ": " + error.getMessage(),
                        error);
            }
        }
        started = true;
    }

    synchronized void stop(Function<AuraModule, ModuleContext> contexts) {
        if (!startedModules.isEmpty()) {
            stopStarted(contexts, null);
        } else {
            stopModules(installedModules, contexts, null);
            installedModules.clear();
            started = false;
        }
    }

    private void stopStarted(Function<AuraModule, ModuleContext> contexts, Throwable primaryFailure) {
        stopModules(startedModules, contexts, primaryFailure);
        startedModules.clear();
        started = false;
    }

    private void stopModules(List<AuraModule> targets, Function<AuraModule, ModuleContext> contexts,
                             Throwable primaryFailure) {
        for (int i = targets.size() - 1; i >= 0; i--) {
            AuraModule module = targets.get(i);
            try {
                module.stop(contexts.apply(module));
            } catch (Throwable error) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(error);
                } else {
                    org.slf4j.LoggerFactory.getLogger(ModuleManager.class)
                            .error("Error stopping module {}", module.descriptor().id(), error);
                }
            }
        }
    }
}
