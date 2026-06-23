package io.aura.dev;

import io.aura.Aura;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DevModeTest {

    @AfterEach
    void cleanup() {
        Aura.clearReloadInstance();
    }

    @Test
    void devMode_flag_setByArg() {
        var app = Aura.create();
        app.dev(true);
        assertThat(Aura.isDevMode()).isTrue();
        app.dev(false);
    }

    @Test
    void reloadInstance_clearOnReset() {
        var app = Aura.create();
        Aura.setReloadInstance(app);
        var reloaded = Aura.create();
        assertThat(reloaded).isSameAs(app);
        Aura.clearReloadInstance();
        var fresh = Aura.create();
        assertThat(fresh).isNotSameAs(app);
    }

    @Test
    void reloadCreate_clearsRoutesAndServices() {
        var app = Aura.create();
        app.get("/test", (io.aura.web.BaseHandler) ctx -> ctx.text("hi"));
        app.service(new Object());
        assertThat(app.directRoutes()).isNotEmpty();
        assertThat(app.services()).isNotEmpty();

        Aura.setReloadInstance(app);
        Aura.create();
        assertThat(app.directRoutes()).isEmpty();
        assertThat(app.services()).isEmpty();
    }

    @Test
    void onReload_hookIsCalled() {
        var app = Aura.create();
        boolean[] called = {false};
        app.onReload(() -> called[0] = true);
        app.fireReloadCleanup();
        assertThat(called[0]).isTrue();
    }
}
