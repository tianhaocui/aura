package io.aura;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TlsConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsTlsPropertiesAndBuildsContextWithoutExposingPassword() throws Exception {
        String password = "changeit";
        Path keyStorePath = tempDir.resolve("server.p12");
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, password.toCharArray());
        try (OutputStream output = Files.newOutputStream(keyStorePath)) {
            keyStore.store(output, password.toCharArray());
        }
        Properties properties = new Properties();
        properties.setProperty("aura.tls.keystore-path", keyStorePath.toString());
        properties.setProperty("aura.tls.keystore-password", password);

        AuraApp app = AuraApp.create(properties);

        assertThat(app.config().tlsEnabled()).isTrue();
        assertThat(app.config().keyStorePath()).isEqualTo(keyStorePath.toString());
        assertThat(((AuraWebRuntime) app.runtimeView()).tlsContext()).isNotNull();
        assertThat(java.util.Arrays.stream(AuraConfigSnapshot.class.getMethods())
                .map(java.lang.reflect.Method::getName)).doesNotContain("keyStorePassword");
    }

    @Test
    void rejectsTlsPasswordWithoutKeystorePath() {
        Properties properties = new Properties();
        properties.setProperty("aura.tls.keystore-password", "secret");

        assertThatThrownBy(() -> AuraConfig.fromProperties(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keystore-path");
    }
}
