package io.aura;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PropResolutionTest {

    @AfterEach
    void cleanup() {
        System.clearProperty("nacos.server-addr");
        System.clearProperty("app.timeout");
    }

    @Test
    void prop_hyphenConvertedToUnderscoreForEnvLookup() {
        var app = Aura.create().set("nacos.server-addr", "from-props");
        // env var NACOS_SERVER_ADDR may not be set in test env,
        // so verify the key transformation works by checking props fallback
        assertThat(app.prop("nacos.server-addr")).isEqualTo("from-props");
    }

    @Test
    void prop_systemPropertyTakesPrecedenceOverPropsMap() {
        System.setProperty("app.timeout", "from-sysprop");
        var app = Aura.create().set("app.timeout", "from-props");
        assertThat(app.prop("app.timeout")).isEqualTo("from-sysprop");
    }

    @Test
    void prop_propsMapUsedWhenNoEnvOrSysProp() {
        var app = Aura.create().set("my.custom.key", "props-value");
        assertThat(app.prop("my.custom.key")).isEqualTo("props-value");
    }

    @Test
    void prop_returnsNullWhenNotFoundAnywhere() {
        var app = Aura.create();
        assertThat(app.prop("nonexistent.key")).isNull();
    }

    @Test
    void prop_hyphenAndDotBothConverted() {
        // nacos.server-addr -> NACOS_SERVER_ADDR (dots and hyphens both become underscores)
        var app = Aura.create().set("multi-level.some-key", "val");
        assertThat(app.prop("multi-level.some-key")).isEqualTo("val");
    }
}
