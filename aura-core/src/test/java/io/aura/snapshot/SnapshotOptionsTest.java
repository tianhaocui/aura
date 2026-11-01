package io.aura.snapshot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotOptionsTest {

    @Test
    void defaultRedactFieldsCoverCommonSecretNames() {
        SnapshotOptions options = new SnapshotOptions();
        assertThat(options.redactFields())
                .contains("api_key", "apikey", "key", "credential", "bearer")
                .contains("password", "token", "secret");
    }

    @Test
    void defaultRedactHeadersKeepExactMatches() {
        SnapshotOptions options = new SnapshotOptions();
        assertThat(options.redactHeaders())
                .contains("authorization", "cookie", "set-cookie", "x-api-key");
    }

    @Test
    void copyPreservesExpandedDefaults() {
        SnapshotOptions original = new SnapshotOptions();
        assertThat(new SnapshotOptions().copy().redactFields())
                .containsExactlyInAnyOrderElementsOf(original.redactFields());
        assertThat(new SnapshotOptions().copy().redactHeaders())
                .containsExactlyInAnyOrderElementsOf(original.redactHeaders());
    }

    @Test
    void redactFieldAddsCustomSecretName() {
        SnapshotOptions options = new SnapshotOptions().redactField("stripeApiKey");
        assertThat(options.redactFields()).contains("stripeApiKey");
    }
}
