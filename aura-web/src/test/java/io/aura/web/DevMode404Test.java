package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;
import io.aura.contract.RouteContribution;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DevMode404Test {

    @Test
    void devModeReturnsJsonDiagnostic() {
        AuraApp app = app(
                new RouteContribution("GET", "/api/users", (BaseHandler) ctx -> ctx.json("ok")),
                new RouteContribution("POST", "/api/users", (BaseHandler) ctx -> ctx.json("ok")));

        TestClient client = TestClient.of(app);
        TestClient.Response res = client.get("/api/user").execute();

        assertEquals(404, res.status());
        String body = res.body();
        assertTrue(body.contains("No route matched"));
        assertTrue(body.contains("/api/user"));
        assertTrue(body.contains("registered"));
    }

    @Test
    void devModeShowsHintForSimilarRoute() {
        AuraApp app = app(new RouteContribution(
                "POST", "/api/users", (BaseHandler) ctx -> ctx.json("ok")));

        TestClient client = TestClient.of(app);
        TestClient.Response res = client.post("/api/user").execute();

        assertEquals(404, res.status());
        assertTrue(res.body().contains("Did you mean"));
    }

    @Test
    void devModeIsScopedToOneApplication() {
        AuraApp development = app(new RouteContribution(
                "GET", "/known", (BaseHandler) ctx -> ctx.text("ok")));
        AuraApp production = AuraApp.create().prepare();

        assertTrue(TestClient.of(development).get("/missing").execute().body().contains("registered"));
        assertEquals("Not Found", TestClient.of(production).get("/missing").execute().body());
    }

    private static AuraApp app(RouteContribution... routes) {
        AuraModule module = new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("dev-routes", "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                for (RouteContribution route : routes) context.route(route);
            }
        };
        return AuraApp.create(new AuraConfig().devMode(true)).use(module).prepare();
    }
}
