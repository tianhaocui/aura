package io.aura;

import io.aura.contract.ModuleMetadata;

import java.util.List;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Owns deterministic module registration and lifecycle. */
final class ModuleManager {
    private final List<AuraModule> modules = new CopyOnWriteArrayList<>();
    private final List<ModuleMetadata> installedDescriptors = new CopyOnWriteArrayList<>();
    private final List<AuraModule> installedModules = new CopyOnWriteArrayList<>();
    private final List<AuraModule> startedModules = new CopyOnWriteArrayList<>();
    private final Map<AuraModule, ModuleMetadata> descriptorCache =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private volatile boolean installed;
    private volatile boolean started;

    synchronized void add(AuraModule module) {
        if (module == null) throw new IllegalArgumentException("module must not be null");
        if (installed) throw new IllegalStateException("Modules cannot be added after module preparation");
        ModuleMetadata descriptor = module.descriptor();
        if (descriptor == null) {
            throw new ModuleException("AURA_MODULE_DESCRIPTOR_MISSING", "?",
                    "Module descriptor must not be null");
        }
        if (modules.stream().anyMatch(existing -> descriptor(existing).id().equals(descriptor.id()))) {
            throw new ModuleException("AURA_MODULE_DUPLICATE", descriptor.id(),
                    "Duplicate module id: " + descriptor.id());
        }
        descriptorCache.put(module, descriptor);
        modules.add(module);
    }

    List<AuraModule> modules() { return List.copyOf(modules); }

    List<ModuleMetadata> descriptors() {
        if (!installedDescriptors.isEmpty()) return List.copyOf(installedDescriptors);
        return modules.stream().map(this::descriptor).toList();
    }

    ModuleMetadata descriptor(AuraModule module) {
        ModuleMetadata descriptor = descriptorCache.get(module);
        if (descriptor == null) {
            throw new ModuleException("AURA_MODULE_DESCRIPTOR_MISSING", "?",
                    "Module descriptor was not captured during registration");
        }
        return descriptor;
    }

    boolean installed() { return installed; }
    boolean started() { return started; }

    synchronized void install(Function<AuraModule, ModuleContext> contexts, boolean startModules,
                              BiConsumer<AuraModule, Throwable> failureHandler) {
        if (installed) {
            if (startModules && !started) start(contexts, failureHandler);
            return;
        }
        List<AuraModule> ordered = ModuleGraph.order(modules, this::descriptor);
        List<AuraModule> installing = new java.util.ArrayList<>();
        try {
            for (AuraModule module : ordered) {
                ModuleMetadata descriptor = descriptor(module);
                installing.add(module);
                module.install(contexts.apply(module));
                installedDescriptors.add(descriptor);
                installedModules.add(module);
            }
        } catch (Throwable failure) {
            rethrowIfFatal(failure);
            stopModules(installing, contexts, failure, failureHandler);
            installedDescriptors.clear();
            installedModules.clear();
            if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            if (failure instanceof Error errorFailure) throw errorFailure;
            throw new RuntimeException(failure);
        }
        modules.clear();
        modules.addAll(ordered);
        installed = true;
        if (startModules) start(contexts, failureHandler);
    }

    synchronized void start(Function<AuraModule, ModuleContext> contexts,
                            BiConsumer<AuraModule, Throwable> failureHandler) {
        if (started) return;
        for (AuraModule module : modules) {
            try {
                module.start(contexts.apply(module));
                startedModules.add(module);
            } catch (Throwable error) {
                rethrowIfFatal(error);
                try {
                    module.stop(contexts.apply(module));
                } catch (Throwable cleanupFailure) {
                    reportFailure(module, error, cleanupFailure, failureHandler);
                }
                stopStarted(contexts, error, failureHandler);
                installedModules.clear();
                installedDescriptors.clear();
                String moduleId = descriptor(module).id();
                throw new ModuleException("AURA_MODULE_START_FAILED", moduleId,
                        "Failed to start module " + moduleId + ": " + error.getMessage(),
                        error);
            }
        }
        started = true;
    }

    synchronized void stop(Function<AuraModule, ModuleContext> contexts,
                           BiConsumer<AuraModule, Throwable> failureHandler) {
        if (!startedModules.isEmpty()) {
            stopStarted(contexts, null, failureHandler);
        } else {
            stopModules(installedModules, contexts, null, failureHandler);
            installedModules.clear();
            started = false;
        }
    }

    private void stopStarted(Function<AuraModule, ModuleContext> contexts, Throwable primaryFailure,
                             BiConsumer<AuraModule, Throwable> failureHandler) {
        stopModules(startedModules, contexts, primaryFailure, failureHandler);
        startedModules.clear();
        started = false;
    }

    private void stopModules(List<AuraModule> targets, Function<AuraModule, ModuleContext> contexts,
                             Throwable primaryFailure,
                             BiConsumer<AuraModule, Throwable> failureHandler) {
        for (int i = targets.size() - 1; i >= 0; i--) {
            AuraModule module = targets.get(i);
            try {
                module.stop(contexts.apply(module));
            } catch (Throwable error) {
                reportFailure(module, primaryFailure, error, failureHandler);
            }
        }
    }

    private void reportFailure(AuraModule module, Throwable primaryFailure, Throwable cleanupFailure,
                               BiConsumer<AuraModule, Throwable> failureHandler) {
        rethrowIfFatal(cleanupFailure);
        if (failureHandler != null) {
            try {
                failureHandler.accept(module, cleanupFailure);
            } catch (Throwable reportingFailure) {
                rethrowIfFatal(reportingFailure);
                org.slf4j.LoggerFactory.getLogger(ModuleManager.class).error(
                        "Module cleanup failure reporter failed: module={}, failureType={}",
                        descriptor(module).id(), reportingFailure.getClass().getName());
            }
            return;
        }
        if (primaryFailure != null) {
            if (cleanupFailure != primaryFailure) primaryFailure.addSuppressed(cleanupFailure);
            return;
        }
        org.slf4j.LoggerFactory.getLogger(ModuleManager.class).error(
                "Error stopping module {} ({})",
                descriptor(module).id(), cleanupFailure.getClass().getName());
    }

    private static void rethrowIfFatal(Throwable error) {
        if (error instanceof VirtualMachineError fatal) throw fatal;
        if (error instanceof ThreadDeath fatal) throw fatal;
    }
}
