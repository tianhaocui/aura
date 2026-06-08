package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class GzipEdgeTest {

    @Test
    void gzipMinSize_negativeViaBuilder_clampedToZero() {
        Aura app = Aura.create().gzipMinSize(-1);
        assertThat(app.gzipMinSize()).isEqualTo(0);
    }

    @Test
    void gzipMinSize_zeroAllowed() {
        Aura app = Aura.create().gzipMinSize(0);
        assertThat(app.gzipMinSize()).isEqualTo(0);
    }

    @Test
    void gzipMinSize_largeValue() {
        Aura app = Aura.create().gzipMinSize(1024 * 1024);
        assertThat(app.gzipMinSize()).isEqualTo(1024 * 1024);
    }

    @Test
    void gzip_enabledWithSse_routeCompilesCleanly() {
        Aura app = Aura.create().gzip(true);
        Router router = new Router();
        router.get("/events", ctx -> {
            var sse = ctx.sse();
            sse.send("hello");
            sse.close();
        });
        var routes = UndertowStarter.compileRoutes(router);
        assertThat(routes).hasSize(1);
        assertThat(app.gzip()).isTrue();
    }
}
