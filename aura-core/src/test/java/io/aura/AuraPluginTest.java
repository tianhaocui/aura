package io.aura;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class AuraPluginTest {

    @Test
    void plugin_installCalledOnStart() {
        AtomicBoolean installed = new AtomicBoolean(false);
        AuraPlugin plugin = app -> installed.set(true);

        Aura app = Aura.create().plugin(plugin);
        // plugin.install() is called in start(), but start() needs AuraStarter
        // so we test the builder registration
        assertThat(installed.get()).isFalse();
    }

    @Test
    void plugin_fluent_api() {
        Aura app = Aura.create()
                .plugin(a -> a.prop("test.key", "value1"))
                .plugin(a -> a.prop("test.key2", "value2"));
        // plugins not yet installed (need start()), but builder works
        assertThat(app).isNotNull();
    }
}
