package io.aura;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuraModuleTest {

    @Test
    void preparesModulesInDependencyOrderAndExposesContributions() {
        List<String> installed = new ArrayList<>();
        AuraModule dependency = module("dependency", List.of(), installed);
        AuraModule feature = module("feature", List.of("dependency"), installed);

        Aura app = Aura.create().use(feature).use(dependency);
        app.prepareModules();

        assertThat(installed).containsExactly("dependency", "feature");
        assertThat(app.moduleDescriptors()).extracting(ModuleDescriptor::id)
                .containsExactly("dependency", "feature");
        assertThat(app.getBean(String.class)).isEqualTo("feature");
    }

    @Test
    void rejectsMissingAndCyclicDependenciesBeforeInstallation() {
        AuraModule missing = module("feature", List.of("database"), new ArrayList<>());
        assertThatThrownBy(() -> Aura.create().use(missing).prepareModules())
                .isInstanceOf(ModuleException.class)
                .extracting("code").isEqualTo("AURA_MODULE_DEPENDENCY_MISSING");

        AuraModule first = module("first", List.of("second"), new ArrayList<>());
        AuraModule second = module("second", List.of("first"), new ArrayList<>());
        assertThatThrownBy(() -> Aura.create().use(first).use(second).prepareModules())
                .isInstanceOf(ModuleException.class)
                .extracting("code").isEqualTo("AURA_MODULE_DEPENDENCY_CYCLE");
    }

    @Test
    void rollsBackStartedModulesWhenLaterModuleFails() {
        List<String> events = new ArrayList<>();
        AuraModule first = new AuraModule() {
            @Override public ModuleDescriptor descriptor() { return ModuleDescriptor.of("first", "1.0.0").build(); }
            @Override public void install(ModuleContext context) { }
            @Override public void start(ModuleContext context) { events.add("start:first"); }
            @Override public void stop(ModuleContext context) { events.add("stop:first"); }
        };
        AuraModule second = new AuraModule() {
            @Override public ModuleDescriptor descriptor() { return ModuleDescriptor.of("second", "1.0.0").build(); }
            @Override public void install(ModuleContext context) { }
            @Override public void start(ModuleContext context) { throw new IllegalStateException("boom"); }
        };

        assertThatThrownBy(() -> Aura.create().use(first).use(second).start())
                .isInstanceOf(ModuleException.class)
                .extracting("code").isEqualTo("AURA_MODULE_START_FAILED");
        assertThat(events).containsExactly("start:first", "stop:first");
    }

    @Test
    void auraAppIsTheComposableModuleFacade() {
        AuraApp app = AuraApp.create()
                .use(module("sample", List.of(), new ArrayList<>()));

        assertThat(app.modules()).extracting(ModuleDescriptor::id).containsExactly("sample");
        assertThat(app.lifecycle()).isEqualTo(Aura.Lifecycle.NEW);
        app.runtime().prepareModules();
        assertThat(app.lifecycle()).isEqualTo(Aura.Lifecycle.NEW);
    }

    private static AuraModule module(String id, List<String> dependencies, List<String> installed) {
        return new AuraModule() {
            @Override public ModuleDescriptor descriptor() {
                ModuleDescriptor.Builder builder = ModuleDescriptor.of(id, "1.0.0");
                dependencies.forEach(dep -> builder.requires(dep));
                return builder.build();
            }

            @Override public void install(ModuleContext context) {
                installed.add(id);
                context.register(id);
            }
        };
    }
}
