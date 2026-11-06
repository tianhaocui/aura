package io.aura.web;

import io.aura.AuraConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class GzipEdgeTest {

    @Test
    void gzipMinSize_negative_clampedToZero() {
        AuraConfig config = new AuraConfig().gzipMinSize(-1);
        assertThat(config.gzipMinSize()).isEqualTo(0);
    }

    @Test
    void gzipMinSize_zeroAllowed() {
        AuraConfig config = new AuraConfig().gzipMinSize(0);
        assertThat(config.gzipMinSize()).isEqualTo(0);
    }

    @Test
    void gzipMinSize_largeValue() {
        AuraConfig config = new AuraConfig().gzipMinSize(1024 * 1024);
        assertThat(config.gzipMinSize()).isEqualTo(1024 * 1024);
    }

    @Test
    void gzip_enabledWithSse_routeCompilesCleanly() {
        AuraConfig config = new AuraConfig().gzip(true);
        Router router = new Router();
        router.get("/events", ctx -> {
            var sse = ctx.sse();
            sse.send("hello");
            sse.close();
        });
        var routes = UndertowStarter.compileRoutes(router);
        assertThat(routes).hasSize(1);
        assertThat(config.gzip()).isTrue();
    }
}
