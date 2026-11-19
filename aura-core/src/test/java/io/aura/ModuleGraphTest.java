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
    void wildcardRangesPinEveryComponentBeforeTheStar() {
        assertThat(ModuleGraph.versionMatches("1.2.9", "1.2.*")).isTrue();
        assertThat(ModuleGraph.versionMatches("1.3.0", "1.2.*")).isFalse();
        assertThat(ModuleGraph.versionMatches("1.9.0", "1.2.*")).isFalse();
        assertThat(ModuleGraph.versionMatches("2.2.0", "1.2.*")).isFalse();
        assertThat(ModuleGraph.versionMatches("1.9.0", "1.*")).isTrue();
        assertThat(ModuleGraph.versionMatches("2.0.0", "1.*")).isFalse();
        assertThat(ModuleGraph.versionMatches("0.2.9", "0.2.*")).isTrue();
        assertThat(ModuleGraph.versionMatches("0.3.0", "0.2.*")).isFalse();
    }

    @Test
    void rejectsInvalidVersionsInsteadOfDegradingToZero() {
        for (String invalid : new String[]{null, "", "   ", "not-a-version", "1.2.3.4",
                "1.2.3-SNAPSHOT", "1.0.0+build", "1.x", "01.2.3", "1.02.3", "1..3",
                "99999999999.0.0", "v1.2.3"}) {
            assertThatThrownBy(() -> ModuleGraph.versionMatches(invalid, "*"))
                    .as("version %s", invalid)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsInvalidRangesInsteadOfPassingSilently() {
        for (String invalid : new String[]{"^abc", "~1.x", "1.2.3.*", "1..*", "[1.0",
                "[1.0,2.0", "1.0.0-SNAPSHOT", "garbage"}) {
            assertThatThrownBy(() -> ModuleGraph.versionMatches("1.2.3", invalid))
                    .as("range %s", invalid)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void graphRejectsInvalidDeclaredVersionsAndConstraintsWithStableCode() {
        AuraModule broken = moduleWithVersion("broken", "1.0.0-SNAPSHOT");
        assertThatThrownBy(() -> ModuleGraph.order(List.of(broken)))
                .isInstanceOf(ModuleException.class)
                .extracting("code").isEqualTo("AURA_MODULE_VERSION_INVALID");

        AuraModule storage = module("storage", List.of());
        AuraModule consumer = moduleWithRequirement("consumer", "storage", "^not-a-version");
        assertThatThrownBy(() -> ModuleGraph.order(List.of(consumer, storage)))
                .isInstanceOf(ModuleException.class)
                .extracting("code").isEqualTo("AURA_MODULE_VERSION_INVALID");
    }

    @Test
    void caretRangesFollowSemverCompatibilityRules() {
        assertThat(ModuleGraph.versionMatches("1.9.0", "^1.2.3")).isTrue();
        assertThat(ModuleGraph.versionMatches("2.0.0", "^1.2.3")).isFalse();
        assertThat(ModuleGraph.versionMatches("0.2.9", "^0.2.3")).isTrue();
        assertThat(ModuleGraph.versionMatches("0.3.0", "^0.2.3")).isFalse();
        assertThat(ModuleGraph.versionMatches("0.9.0", "^0.1.2")).isFalse();
        assertThat(ModuleGraph.versionMatches("0.0.3", "^0.0.3")).isTrue();
        assertThat(ModuleGraph.versionMatches("0.0.4", "^0.0.3")).isFalse();
    }

    @Test
    void supportsOpenEndedRanges() {
        assertThat(ModuleGraph.versionMatches("1.5.0", "[,2.0)")).isTrue();
        assertThat(ModuleGraph.versionMatches("1.5.0", "[1.0,)")).isTrue();
        assertThat(ModuleGraph.versionMatches("2.1.0", "(2.0,)")).isTrue();
        assertThat(ModuleGraph.versionMatches("2.0.0", "(2.0,)")).isFalse();
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

    private static AuraModule moduleWithVersion(String id, String version) {
        return new AuraModule() {
            @Override public ModuleMetadata descriptor() { return ModuleMetadata.of(id, version); }
            @Override public void install(ModuleContext context) { }
        };
    }

    private static AuraModule moduleWithRequirement(String id, String dependency, String range) {
        return new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of(id, "1.0.0").requires(dependency, range);
            }
            @Override public void install(ModuleContext context) { }
        };
    }
}
