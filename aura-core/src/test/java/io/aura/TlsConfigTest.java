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
    void rejectsEmptyTlsKeystoreBeforeStartupWithoutExposingPassword() throws Exception {
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

        AuraConfig config = AuraConfig.fromProperties(properties);

        assertThat(config.tlsEnabled()).isTrue();
        assertThat(config.keyStorePath()).isEqualTo(keyStorePath.toString());
        assertThat(config.properties()).doesNotContainKey("aura.tls.keystore-password");
        assertThat(java.util.Arrays.stream(AuraConfigSnapshot.class.getMethods())
                .map(java.lang.reflect.Method::getName)).doesNotContain("keyStorePassword");
        assertThatThrownBy(() -> AuraApp.create(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PrivateKeyEntry");
    }

    @Test
    void rejectsTlsPasswordWithoutKeystorePath() {
        Properties properties = new Properties();
        properties.setProperty("aura.tls.keystore-password", "secret");

        assertThatThrownBy(() -> AuraConfig.fromProperties(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keystore-path");
        assertThatThrownBy(() -> new AuraConfig()
                .property("aura.tls.keystore-password", "secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tls(...)");
    }
}
