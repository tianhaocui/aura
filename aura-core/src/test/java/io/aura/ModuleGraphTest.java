package io.aura;

import io.aura.contract.ModuleMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleGraphTest {
    @Test
    void ordersDependenciesDeterministicallyWithoutAnApplicationInstance() {
        AuraModule feature = module("feature", List.of("storage"));
        AuraModule storage = module("storage", List.of("contract"));
        AuraModule contract = module("contract", List.of());

        assertThat(ModuleGraph.order(List.of(feature, storage, contract)))
                .extracting(module -> module.descriptor().id())
                .containsExactly("contract", "storage", "feature");
    }

    @Test
    void validatesSupportedVersionRangeForms() {
        assertThat(ModuleGraph.versionMatches("1.4.2", "1.*")).isTrue();
        assertThat(ModuleGraph.versionMatches("1.4.2", "^1.0.0")).isTrue();
        assertThat(ModuleGraph.versionMatches("1.4.2", "~1.4.0")).isTrue();
        assertThat(ModuleGraph.versionMatches("1.4.2", "[1.0,2.0)")).isTrue();
        assertThat(ModuleGraph.versionMatches("2.0.0", "[1.0,2.0)")).isFalse();
    }

    @Test
    void reportsMissingAndCyclicDependenciesWithStableCodes() {
        assertThatThrownBy(() -> ModuleGraph.order(List.of(module("orders", List.of("db")))))
                .isInstanceOf(ModuleException.class)
                .extracting("code").isEqualTo("AURA_MODULE_DEPENDENCY_MISSING");

        AuraModule first = module("first", List.of("second"));
        AuraModule second = module("second", List.of("first"));
        assertThatThrownBy(() -> ModuleGraph.order(List.of(first, second)))
                .isInstanceOf(ModuleException.class)
                .extracting("code").isEqualTo("AURA_MODULE_DEPENDENCY_CYCLE");
    }

    private static AuraModule module(String id, List<String> dependencies) {
        return new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                ModuleMetadata metadata = ModuleMetadata.of(id, "1.0.0");
                for (String dependency : dependencies) metadata = metadata.requires(dependency);
                return metadata;
            }
            @Override public void install(ModuleContext context) { }
        };
    }
}
