package io.aura;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuraConfigFileTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsAndFreezesValidatedDeploymentProperties() throws Exception {
        Path file = tempDir.resolve("aura.properties");
        Files.writeString(file, """
                aura.port=9999
                aura.env=production
                aura.workers=50
                aura.gzip=true
                aura.request-timeout=30
                aura.gzip-min-size=2048
                aura.access-log=json
                aura.cors=https://myapp.example
                aura.rate-limit-max-keys=4096
                """);

        AuraConfigSnapshot config = AuraApp.create(file).config();

        assertThat(config.port()).isEqualTo(9999);
        assertThat(config.env()).isEqualTo("production");
        assertThat(config.workers()).isEqualTo(50);
        assertThat(config.gzip()).isTrue();
        assertThat(config.requestTimeout()).isEqualTo(30);
        assertThat(config.gzipMinSize()).isEqualTo(2048);
        assertThat(config.accessLogFormat()).isEqualTo("json");
        assertThat(config.corsOrigin()).isEqualTo("https://myapp.example");
        assertThat(config.rateLimitMaxKeys()).isEqualTo(4096);
    }

    @Test
    void rejectsInvalidValuesInsteadOfSilentlyClampingThem() throws Exception {
        Path file = tempDir.resolve("invalid.properties");
        Files.writeString(file, "aura.request-timeout=-5\n");

        assertThatThrownBy(() -> AuraApp.create(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestTimeout");
    }

    @Test
    void missingConfigurationFileFailsAtCreation() {
        assertThatThrownBy(() -> AuraApp.create(tempDir.resolve("missing.properties")))
                .isInstanceOf(IOException.class);
    }

    @Test
    void defaultApplicationReadsJvmDeploymentOverrides() {
        String previousPort = System.getProperty("aura.port");
        String previousEnv = System.getProperty("aura.env");
        try {
            System.setProperty("aura.port", "9197");
            System.setProperty("aura.env", "production");

            AuraConfigSnapshot config = AuraApp.create().config();

            assertThat(config.port()).isEqualTo(9197);
            assertThat(config.env()).isEqualTo("production");
        } finally {
            restoreProperty("aura.port", previousPort);
            restoreProperty("aura.env", previousEnv);
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
