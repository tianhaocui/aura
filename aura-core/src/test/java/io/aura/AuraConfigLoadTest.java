package io.aura;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileWriter;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class AuraConfigLoadTest {

    @TempDir
    Path tempDir;

    @Test
    void loadExternalConfig_appliesFrameworkProps() throws Exception {
        Path config = tempDir.resolve("test.properties");
        try (var w = new FileWriter(config.toFile())) {
            w.write("aura.port=9999\n");
            w.write("aura.env=production\n");
            w.write("aura.workers=50\n");
            w.write("aura.gzip=true\n");
            w.write("aura.request-timeout=30\n");
            w.write("aura.gzip-min-size=2048\n");
        }

        var method = Aura.class.getDeclaredMethod("loadExternalConfig", String.class);
        method.setAccessible(true);

        Aura app = Aura.create();
        method.invoke(app, config.toString());

        assertThat(app.port()).isEqualTo(9999);
        assertThat(app.env()).isEqualTo("production");
        assertThat(app.workers()).isEqualTo(50);
        assertThat(app.gzip()).isTrue();
        assertThat(app.requestTimeout()).isEqualTo(30);
        assertThat(app.gzipMinSize()).isEqualTo(2048);
    }

    @Test
    void loadExternalConfig_requestTimeoutNegative_clampedToZero() throws Exception {
        Path config = tempDir.resolve("test.properties");
        try (var w = new FileWriter(config.toFile())) {
            w.write("aura.request-timeout=-5\n");
            w.write("aura.gzip-min-size=-100\n");
        }

        var method = Aura.class.getDeclaredMethod("loadExternalConfig", String.class);
        method.setAccessible(true);

        Aura app = Aura.create();
        method.invoke(app, config.toString());

        assertThat(app.requestTimeout()).isEqualTo(0);
        assertThat(app.gzipMinSize()).isEqualTo(0);
    }

    @Test
    void loadExternalConfig_accessLogFormats() throws Exception {
        Path config = tempDir.resolve("test.properties");
        try (var w = new FileWriter(config.toFile())) {
            w.write("aura.access-log=json\n");
        }

        var method = Aura.class.getDeclaredMethod("loadExternalConfig", String.class);
        method.setAccessible(true);

        Aura app = Aura.create();
        method.invoke(app, config.toString());

        assertThat(app.accessLogFormat()).isEqualTo("json");
    }

    @Test
    void loadExternalConfig_accessLogTrue_setsText() throws Exception {
        Path config = tempDir.resolve("test.properties");
        try (var w = new FileWriter(config.toFile())) {
            w.write("aura.access-log=true\n");
        }

        var method = Aura.class.getDeclaredMethod("loadExternalConfig", String.class);
        method.setAccessible(true);

        Aura app = Aura.create();
        method.invoke(app, config.toString());

        assertThat(app.accessLogFormat()).isEqualTo("text");
    }

    @Test
    void loadExternalConfig_accessLogFalse_setsNull() throws Exception {
        Path config = tempDir.resolve("test.properties");
        try (var w = new FileWriter(config.toFile())) {
            w.write("aura.access-log=false\n");
        }

        var method = Aura.class.getDeclaredMethod("loadExternalConfig", String.class);
        method.setAccessible(true);

        Aura app = Aura.create();
        method.invoke(app, config.toString());

        assertThat(app.accessLogFormat()).isNull();
    }

    @Test
    void loadExternalConfig_corsSpecificOrigin() throws Exception {
        Path config = tempDir.resolve("test.properties");
        try (var w = new FileWriter(config.toFile())) {
            w.write("aura.cors=https://myapp.com\n");
        }

        var method = Aura.class.getDeclaredMethod("loadExternalConfig", String.class);
        method.setAccessible(true);

        Aura app = Aura.create();
        method.invoke(app, config.toString());

        assertThat(app.corsOrigin()).isEqualTo("https://myapp.com");
    }

    @Test
    void loadExternalConfig_nonexistentFile_doesNotThrow() throws Exception {
        var method = Aura.class.getDeclaredMethod("loadExternalConfig", String.class);
        method.setAccessible(true);

        Aura app = Aura.create();
        assertThatCode(() -> method.invoke(app, "/nonexistent/path.properties"))
                .doesNotThrowAnyException();
    }
}
