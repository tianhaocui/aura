package io.aura.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class RoutePriorityTest {

    @Test
    void longerPathWinsWhenSameParamCount() {
        Router router = new Router();
        router.get("/api/rides/{id}", ctx -> {});
        router.get("/api/rides/{id}/gpx", ctx -> {});

        List<CompiledRoute> routes = UndertowStarter.compileRoutes(router);

        // /api/rides/{id}/gpx has more segments → should come first
        assertThat(routes.get(0).rawPath()).isEqualTo("/api/rides/{id}/gpx");
        assertThat(routes.get(1).rawPath()).isEqualTo("/api/rides/{id}");
    }

    @Test
    void staticRoutesBeforeParamRoutes() {
        Router router = new Router();
        router.get("/api/items/{id}", ctx -> {});
        router.get("/api/items/search", ctx -> {});

        List<CompiledRoute> routes = UndertowStarter.compileRoutes(router);

        // static route (0 params) should come before parameterized (1 param)
        assertThat(routes.get(0).rawPath()).isEqualTo("/api/items/search");
        assertThat(routes.get(1).rawPath()).isEqualTo("/api/items/{id}");
    }

    @Test
    void multipleParamRoutesOrderedBySegmentCount() {
        Router router = new Router();
        router.get("/a/{x}", ctx -> {});
        router.get("/a/{x}/b/{y}", ctx -> {});
        router.get("/a/{x}/b/{y}/c", ctx -> {});

        List<CompiledRoute> routes = UndertowStarter.compileRoutes(router);

        // 0 params first, then 1 param, then 2 params — within same param count, more segments first
        assertThat(routes.get(0).rawPath()).isEqualTo("/a/{x}");
        // 2-param routes: /a/{x}/b/{y}/c (5 segments) before /a/{x}/b/{y} (4 segments)
        assertThat(routes.get(1).rawPath()).isEqualTo("/a/{x}/b/{y}/c");
        assertThat(routes.get(2).rawPath()).isEqualTo("/a/{x}/b/{y}");
    }
}
