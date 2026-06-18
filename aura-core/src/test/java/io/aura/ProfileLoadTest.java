package io.aura;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileLoadTest {

    @Test
    void defaultEnv_loadsDevProfile() {
        Aura app = Aura.create();
        assertThat(app.env()).isEqualTo("dev");
        assertThat(app.prop("app.name")).isEqualTo("dev-profile");
    }

    @Test
    void profileOverridesBase() {
        // aura.properties sets app.name=base, aura-dev.properties overrides to dev-profile
        Aura app = Aura.create();
        assertThat(app.prop("app.name")).isEqualTo("dev-profile");
        assertThat(app.port()).isEqualTo(8080);
    }
}
