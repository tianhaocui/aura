package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.contract.RouteContribution;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DuplicateRouteTest {

    @Test
    void duplicateRoute_throwsOnStart() {
        AuraApp app = app("duplicate-route",
                route("GET", "/api/users", "first"),
                route("GET", "/api/users", "second"));
        assertThatThrownBy(app::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate route: GET /api/users");
    }

    @Test
    void differentMethods_samePath_noDuplicate() throws Exception {
        AuraApp app = app("different-methods",
                route("GET", "/api/users", "get"),
                route("POST", "/api/users", "post"));
        try {
            app.start();
        } finally {
            app.stop();
        }
    }

    @Test
    void differentPaths_sameMethod_noDuplicate() throws Exception {
        AuraApp app = app("different-paths",
                route("GET", "/api/users", "users"),
                route("GET", "/api/items", "items"));
        try {
            app.start();
        } finally {
            app.stop();
        }
    }

    @Test
    void duplicateRoute_detectedInCompiledRoutes() {
        Router router = new Router();
        router.get("/api/items", ctx -> ctx.text("first"));
        router.get("/api/items", ctx -> ctx.text("second"));

        var routes = UndertowStarter.compileRoutes(router);
        long count = routes.stream()
                .filter(r -> r.method().equals("GET") && r.rawPath().equals("/api/items"))
                .count();
        assertThat(count).isEqualTo(2);
    }

    @Test
    void testClient_rejectsDuplicateRoutesLikeRealServer() {
        AuraApp app = TestApplications.app("duplicate-test",
                route("GET", "/same", "first"),
                route("GET", "/same", "second"));

        assertThatThrownBy(() -> TestClient.of(app))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate route: GET /same");
    }

    @Test
    void duplicateParameterizedRoute_throwsOnStart() {
        AuraApp app = app("duplicate-parameterized-route",
                route("GET", "/api/users/{id}", "first"),
                route("GET", "/api/users/{id}", "second"));
        assertThatThrownBy(app::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate route");
    }

    @Test
    void duplicateRouteInGroup_throwsOnStart() {
        AuraApp app = app("duplicate-resolved-group-route",
                route("GET", "/api/users", "first"),
                route("GET", "/api/users", "second"));
        assertThatThrownBy(app::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate route: GET /api/users");
    }

    private static AuraApp app(String moduleId, RouteContribution... routes) {
        return TestApplications.app(new AuraConfig().port(0), moduleId, routes);
    }

    private static RouteContribution route(String method, String path, String response) {
        return new RouteContribution(method, path, (BaseHandler) ctx -> ctx.text(response));
    }
}
