package io.aura;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuraModuleTest {
    record OrderConfig(int limit, boolean enabled) {}

    @Test
    void preparesModulesInDependencyOrderAndExposesContributions() {
        List<String> installed = new ArrayList<>();
        AuraModule dependency = module("dependency", List.of(), installed);
        AuraModule feature = module("feature", List.of("dependency"), installed);

        AuraApp app = AuraApp.create().use(feature).use(dependency);
        app.prepare();

        assertThat(installed).containsExactly("dependency", "feature");
        assertThat(app.modules()).extracting(io.aura.contract.ModuleMetadata::id)
                .containsExactly("dependency", "feature");
        assertThat(app.require(String.class)).isEqualTo("feature");
    }

    @Test
    void rejectsMissingAndCyclicDependenciesBeforeInstallation() {
        AuraModule missing = module("feature", List.of("database"), new ArrayList<>());
        assertThatThrownBy(() -> AuraApp.create().use(missing).prepare())
                .isInstanceOf(ModuleException.class)
                .extracting("code").isEqualTo("AURA_MODULE_DEPENDENCY_MISSING");

        AuraModule first = module("first", List.of("second"), new ArrayList<>());
        AuraModule second = module("second", List.of("first"), new ArrayList<>());
        assertThatThrownBy(() -> AuraApp.create().use(first).use(second).prepare())
                .isInstanceOf(ModuleException.class)
                .extracting("code").isEqualTo("AURA_MODULE_DEPENDENCY_CYCLE");
    }

    @Test
    void rollsBackStartedModulesWhenLaterModuleFails() {
        List<String> events = new ArrayList<>();
        AuraModule first = new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() { return io.aura.contract.ModuleMetadata.of("first", "1.0.0"); }
            @Override public void install(ModuleContext context) { }
            @Override public void start(ModuleContext context) { events.add("start:first"); }
            @Override public void stop(ModuleContext context) { events.add("stop:first"); }
        };
        AuraModule second = new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() { return io.aura.contract.ModuleMetadata.of("second", "1.0.0"); }
            @Override public void install(ModuleContext context) { }
            @Override public void start(ModuleContext context) { throw new IllegalStateException("boom"); }
            @Override public void stop(ModuleContext context) {
                events.add("stop:second");
                throw new IllegalArgumentException("cleanup failed");
            }
        };

        assertThatThrownBy(() -> AuraApp.create().use(first).use(second).start())
                .isInstanceOfSatisfying(ModuleException.class, error -> {
                    assertThat(error.code()).isEqualTo("AURA_MODULE_START_FAILED");
                    assertThat(error.getCause()).isInstanceOf(IllegalStateException.class);
                    assertThat(error.causeCode()).isNull();
                    assertThat(error.getSuppressed()).singleElement()
                            .isInstanceOf(IllegalArgumentException.class)
                            .satisfies(suppressed -> assertThat(suppressed.getMessage())
                                    .isEqualTo("cleanup failed"));
                });
        assertThat(events).containsExactly("start:first", "stop:second", "stop:first");
    }

    @Test
    void rollsBackModulesWhenStartThrowsError() {
        List<String> events = new ArrayList<>();
        AuraModule first = new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                return io.aura.contract.ModuleMetadata.of("first-error", "1.0.0");
            }
            @Override public void install(ModuleContext context) { }
            @Override public void start(ModuleContext context) { events.add("start:first"); }
            @Override public void stop(ModuleContext context) { events.add("stop:first"); }
        };
        AuraModule broken = new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                return io.aura.contract.ModuleMetadata.of("broken-error", "1.0.0");
            }
            @Override public void install(ModuleContext context) { }
            @Override public void start(ModuleContext context) { throw new AssertionError("boom"); }
            @Override public void stop(ModuleContext context) { events.add("stop:broken"); }
        };

        assertThatThrownBy(() -> AuraApp.create().use(first).use(broken).start())
                .isInstanceOfSatisfying(ModuleException.class, error -> {
                    assertThat(error.code()).isEqualTo("AURA_MODULE_START_FAILED");
                    assertThat(error.getCause()).isInstanceOf(AssertionError.class);
                });
        assertThat(events).containsExactly("start:first", "stop:broken", "stop:first");
    }

    @Test
    void startFailureRetainsAllRollbackFailuresAsSuppressed() {
        AuraModule first = new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                return io.aura.contract.ModuleMetadata.of("rollback-failure", "1.0.0");
            }
            @Override public void install(ModuleContext context) { }
            @Override public void stop(ModuleContext context) { throw new IllegalStateException("first cleanup"); }
        };
        AuraModule broken = new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                return io.aura.contract.ModuleMetadata.of("start-failure", "1.0.0");
            }
            @Override public void install(ModuleContext context) { }
            @Override public void start(ModuleContext context) { throw new IllegalArgumentException("start failed"); }
            @Override public void stop(ModuleContext context) { throw new IllegalStateException("current cleanup"); }
        };

        assertThatThrownBy(() -> AuraApp.create().use(first).use(broken).start())
                .isInstanceOfSatisfying(ModuleException.class, error ->
                        assertThat(error.getSuppressed())
                                .extracting(Throwable::getMessage)
                                .containsExactly("current cleanup", "first cleanup"));
    }

    @Test
    void closingPreparedAppStopsInstalledModules() {
        List<String> events = new ArrayList<>();
        AuraModule module = new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                return io.aura.contract.ModuleMetadata.of("prepared-cleanup", "1.0.0");
            }
            @Override public void install(ModuleContext context) { events.add("install"); }
            @Override public void stop(ModuleContext context) { events.add("stop"); }
        };

        AuraApp app = AuraApp.create().use(module);
        app.prepare();
        app.close();
        assertThat(events).containsExactly("install", "stop");
    }

    @Test
    void stopRequestedInsideModuleStartIsDeferredAndExecutedExactlyOnce() {
        List<String> events = new ArrayList<>();
        List<AuraEvent> runtimeEvents = new ArrayList<>();
        AuraModule module = new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                return io.aura.contract.ModuleMetadata.of("self-stopping", "1.0.0");
            }

            @Override public void install(ModuleContext context) { events.add("install"); }

            @Override public void start(ModuleContext context) {
                events.add("start");
                context.require(AuraRuntime.class).stop();
                events.add("start-returned");
            }

            @Override public void stop(ModuleContext context) { events.add("stop"); }
        };
        AuraApp app = AuraApp.create().observe(runtimeEvents::add).use(module);

        app.start();
        app.stop();

        assertThat(app.state()).isEqualTo(io.aura.contract.RuntimeState.STOPPED);
        assertThat(app.isRunning()).isFalse();
        assertThat(events).containsExactly("install", "start", "start-returned", "stop");
        assertThat(runtimeEvents).extracting(AuraEvent::type)
                .containsExactly("runtime.shutdown.completed");
    }

    @Test
    void stopRequestedInsideModuleInstallWaitsUntilPreparationCompletes() {
        List<String> events = new ArrayList<>();
        List<AuraEvent> runtimeEvents = new ArrayList<>();
        AuraModule first = new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                return io.aura.contract.ModuleMetadata.of("install-stop-first", "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                events.add("install:first");
                context.require(AuraRuntime.class).stop();
                events.add("install:first-returned");
            }

            @Override public void stop(ModuleContext context) { events.add("stop:first"); }
        };
        AuraModule second = new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                return io.aura.contract.ModuleMetadata.of("install-stop-second", "1.0.0");
            }

            @Override public void install(ModuleContext context) { events.add("install:second"); }
            @Override public void stop(ModuleContext context) { events.add("stop:second"); }
        };
        AuraApp app = AuraApp.create()
                .observe(runtimeEvents::add)
                .use(first)
                .use(second);

        app.prepare();
        app.stop();

        assertThat(app.state()).isEqualTo(io.aura.contract.RuntimeState.STOPPED);
        assertThat(events).containsExactly(
                "install:first", "install:first-returned", "install:second",
                "stop:second", "stop:first");
        assertThat(runtimeEvents).extracting(AuraEvent::type)
                .containsExactly("runtime.shutdown.completed");
    }

    @Test
    void installFailureRollsBackCurrentAndPreviouslyInstalledModules() {
        List<String> events = new ArrayList<>();
        AuraModule first = new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                return io.aura.contract.ModuleMetadata.of("install-first", "1.0.0");
            }
            @Override public void install(ModuleContext context) { events.add("install:first"); }
            @Override public void stop(ModuleContext context) { events.add("stop:first"); }
        };
        AuraModule broken = new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                return io.aura.contract.ModuleMetadata.of("install-broken", "1.0.0");
            }
            @Override public void install(ModuleContext context) {
                events.add("install:broken");
                throw new IllegalStateException("install failed");
            }
            @Override public void stop(ModuleContext context) { events.add("stop:broken"); }
        };

        assertThatThrownBy(() -> AuraApp.create().use(first).use(broken).prepare())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("install failed");
        assertThat(events).containsExactly(
                "install:first", "install:broken", "stop:broken", "stop:first");
    }

    @Test
    void auraAppIsTheComposableModuleFacade() {
        AuraApp app = AuraApp.create()
                .use(module("sample", List.of(), new ArrayList<>()));

        assertThat(app.modules()).extracting(io.aura.contract.ModuleMetadata::id).containsExactly("sample");
        assertThat(app.state()).isEqualTo(io.aura.contract.RuntimeState.NEW);
        assertThat(app.state()).isEqualTo(io.aura.contract.RuntimeState.NEW);
        app.prepare();
        assertThat(app.state()).isEqualTo(io.aura.contract.RuntimeState.NEW);
        assertThat(app.state()).isEqualTo(io.aura.contract.RuntimeState.NEW);
    }

    @Test
    void auraAppExposesCapabilityLookupWithoutLeakingTheRuntime() {
        AuraApp app = AuraApp.create().use(new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                return io.aura.contract.ModuleMetadata.of("capability", "1.0.0");
            }
            @Override public void install(ModuleContext context) { context.register("ready"); }
        });

        app.prepare();
        assertThat(app.find(String.class)).contains("ready");
        assertThatThrownBy(() -> app.require(Integer.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(Integer.class.getName());
    }

    @Test
    void modulesCanRequireTheStableRuntimeContract() {
        AtomicReference<AuraRuntime> seen = new AtomicReference<>();
        AuraApp app = AuraApp.create().use(new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                return io.aura.contract.ModuleMetadata.of("runtime-consumer", "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                seen.set(context.require(AuraRuntime.class));
            }
        });

        app.prepare();

        assertThat(seen).hasValue(app.runtimeView());
    }

    @Test
    void exposesCapabilitiesAndChecksVersionRanges() {
        AuraModule dependency = new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                return io.aura.contract.ModuleMetadata.of("db", "1.4.2").provides("db");
            }
            @Override public void install(ModuleContext context) { }
        };
        AuraModule consumer = new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                return io.aura.contract.ModuleMetadata.of("orders", "1.0.0").requires("db", "[1.0,2.0)");
            }
            @Override public void install(ModuleContext context) { }
        };

        AuraApp app = AuraApp.create().use(consumer).use(dependency);
        app.prepare();
        assertThat(app.modules().get(0).capabilities().contains("db")).isTrue();

        AuraModule incompatible = new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                return io.aura.contract.ModuleMetadata.of("bad", "1.0.0").requires("db", "[2.0,3.0)");
            }
            @Override public void install(ModuleContext context) { }
        };
        assertThatThrownBy(() -> AuraApp.create().use(incompatible).use(dependency).prepare())
                .isInstanceOf(ModuleException.class)
                .extracting("code").isEqualTo("AURA_MODULE_VERSION_MISMATCH");
    }

    @Test
    void bindsModuleScopedRecordConfiguration() {
        AuraModule module = new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                return io.aura.contract.ModuleMetadata.of("orders", "1.0.0");
            }
            @Override public void install(ModuleContext context) {
                OrderConfig config = context.config(OrderConfig.class);
                assertThat(config.limit()).isEqualTo(25);
                assertThat(config.enabled()).isTrue();
            }
        };

        AuraApp.create()
                .property("aura.orders.limit", "25")
                .property("aura.orders.enabled", "true")
                .use(module)
                .prepare();
    }

    @Test
    void modularRecordConfigurationUsesSystemPropertyFallback() {
        String key = "aura.system-config.limit";
        String previous = System.getProperty(key);
        System.setProperty(key, "37");
        try {
            AuraApp app = AuraApp.create().use(new AuraModule() {
                @Override public io.aura.contract.ModuleMetadata descriptor() {
                    return io.aura.contract.ModuleMetadata.of("system-config", "1.0.0");
                }

                @Override public void install(ModuleContext context) {
                    assertThat(context.config(OrderConfig.class).limit()).isEqualTo(37);
                }
            });
            app.prepare();
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }

    @Test
    void moduleContributesPermissionsThroughSharedRegistry() {
        AuraApp app = AuraApp.create().use(new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                return io.aura.contract.ModuleMetadata.of("orders", "1.0.0");
            }
            @Override public void install(ModuleContext context) {
                context.permissions().register("orders.read", "Read orders");
            }
        });

        app.prepare();
        assertThat(app.permissions().contains("orders.read")).isTrue();
    }

    @Test
    void namedCapabilitiesComposeWithoutGlobalLookups() {
        StringBuilder main = new StringBuilder("main");
        StringBuilder audit = new StringBuilder("audit");
        AuraModule databases = new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                return io.aura.contract.ModuleMetadata.of("databases", "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                context.register("main", main);
                context.register("audit", audit);
            }
        };
        AuraApp app = AuraApp.create().use(databases).prepare();

        assertThat(app.require("main", StringBuilder.class)).isSameAs(main);
        assertThat(app.find("audit", CharSequence.class)).contains(audit);
        assertThat(app.find("missing", StringBuilder.class)).isEmpty();
    }

    @Test
    void duplicateNamedCapabilitiesFailPreparation() {
        AuraModule module = new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                return io.aura.contract.ModuleMetadata.of("duplicates", "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                context.register("db", new StringBuilder("first"));
                context.register("db", new StringBuilder("second"));
            }
        };

        assertThatThrownBy(() -> AuraApp.create().use(module).prepare())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void onlyOneModuleCanOwnTheAdmissionPolicy() {
        AuraModule first = admissionModule("first-admission");
        AuraModule second = admissionModule("second-admission");

        assertThatThrownBy(() -> AuraApp.create().use(first).use(second).prepare())
                .isInstanceOf(ModuleException.class)
                .extracting("code").isEqualTo("AURA_ADMISSION_POLICY_DUPLICATE");
    }

    private static AuraModule admissionModule(String id) {
        return new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                return io.aura.contract.ModuleMetadata.of(id, "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                context.admissionPolicy(request -> io.aura.web.AdmissionDecision.allow());
            }
        };
    }

    @Test
    void moduleContributesValidatedWebSocketEndpoint() {
        AuraApp app = AuraApp.create().use(new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                return io.aura.contract.ModuleMetadata.of("events", "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                context.webSocket("/events", socket -> socket.onMessage((connection, text) -> { }));
            }
        });

        app.prepare();

        AuraWebRuntime runtime = (AuraWebRuntime) app.runtimeView();
        assertThat(runtime.webSocketContributions()).singleElement()
                .extracting(WebSocketContribution::path)
                .isEqualTo("/events");
        assertThatThrownBy(() -> new WebSocketContribution("events", handler -> { }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("start with");
    }

    private static AuraModule module(String id, List<String> dependencies, List<String> installed) {
        return new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                io.aura.contract.ModuleMetadata metadata = io.aura.contract.ModuleMetadata.of(id, "1.0.0");
                for (String dep : dependencies) metadata = metadata.requires(dep);
                return metadata;
            }

            @Override public void install(ModuleContext context) {
                installed.add(id);
                context.register(id);
            }
        };
    }
}
