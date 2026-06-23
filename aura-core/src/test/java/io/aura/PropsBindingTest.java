package io.aura;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PropsBindingTest {

    record DbConfig(String url, String username, String password, int poolSize) {}
    record ApiConfig(String key, String baseUrl, long timeout, boolean debug, double rate) {}
    record MinimalConfig(String name) {}

    @Test
    void bindsAllFieldTypes() {
        var app = Aura.create()
                .set("api.key", "secret123")
                .set("api.baseUrl", "https://example.com")
                .set("api.timeout", "5000")
                .set("api.debug", "true")
                .set("api.rate", "1.5");

        ApiConfig config = app.props("api.", ApiConfig.class);
        assertThat(config.key()).isEqualTo("secret123");
        assertThat(config.baseUrl()).isEqualTo("https://example.com");
        assertThat(config.timeout()).isEqualTo(5000L);
        assertThat(config.debug()).isTrue();
        assertThat(config.rate()).isEqualTo(1.5);
    }

    @Test
    void missingFields_useDefaults() {
        var app = Aura.create().set("db.url", "jdbc:h2:mem:test");

        DbConfig config = app.props("db.", DbConfig.class);
        assertThat(config.url()).isEqualTo("jdbc:h2:mem:test");
        assertThat(config.username()).isNull();
        assertThat(config.password()).isNull();
        assertThat(config.poolSize()).isEqualTo(0);
    }

    @Test
    void noMatchingKeys_returnsEmptyRecord() {
        var app = Aura.create().set("other.key", "value");

        MinimalConfig config = app.props("missing.", MinimalConfig.class);
        assertThat(config.name()).isNull();
    }

    @Test
    void multipleBindingsFromSameApp() {
        var app = Aura.create()
                .set("db.main.url", "jdbc:h2:mem:main")
                .set("db.main.poolSize", "10")
                .set("db.analytics.url", "jdbc:h2:mem:analytics")
                .set("db.analytics.poolSize", "5");

        DbConfig main = app.props("db.main.", DbConfig.class);
        DbConfig analytics = app.props("db.analytics.", DbConfig.class);
        assertThat(main.url()).isEqualTo("jdbc:h2:mem:main");
        assertThat(main.poolSize()).isEqualTo(10);
        assertThat(analytics.url()).isEqualTo("jdbc:h2:mem:analytics");
        assertThat(analytics.poolSize()).isEqualTo(5);
    }

    @Test
    void rejectsNonRecordClass() {
        var app = Aura.create();
        assertThatThrownBy(() -> app.props("x.", String.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Record");
    }

    @Test
    void booleanCaseInsensitive() {
        var app = Aura.create().set("flag.debug", "TRUE");

        record FlagConfig(boolean debug) {}
        FlagConfig config = app.props("flag.", FlagConfig.class);
        assertThat(config.debug()).isTrue();
    }

    @Test
    void missingWrapperFields_returnNull() {
        var app = Aura.create();

        record WrapperConfig(Integer count, Long timeout, Double rate, Boolean enabled) {}
        WrapperConfig config = app.props("missing.", WrapperConfig.class);
        assertThat(config.count()).isNull();
        assertThat(config.timeout()).isNull();
        assertThat(config.rate()).isNull();
        assertThat(config.enabled()).isNull();
    }

    @Test
    void primitiveFields_getDefaults_wrapperFields_getNull() {
        var app = Aura.create().set("mix.name", "test");

        record MixConfig(String name, int port, Integer maxConn, long ttl, Long maxSize) {}
        MixConfig config = app.props("mix.", MixConfig.class);
        assertThat(config.name()).isEqualTo("test");
        assertThat(config.port()).isEqualTo(0);
        assertThat(config.maxConn()).isNull();
        assertThat(config.ttl()).isEqualTo(0L);
        assertThat(config.maxSize()).isNull();
    }
}
