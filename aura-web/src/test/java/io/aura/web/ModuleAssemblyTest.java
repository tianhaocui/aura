package io.aura.web;

import io.aura.Aura;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;
import io.aura.contract.ApiParameter;
import io.aura.contract.RouteContribution;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleAssemblyTest {
    @Test
    void moduleRoutesAreAvailableToInMemoryTestClient() {
        Aura app = Aura.create().use(new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("greeting", "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                context.routes(routes -> routes.get("/greeting", ctx -> ctx.text("hello")));
            }
        });

        assertThat(TestClient.of(app).get("/greeting").execute().status()).isEqualTo(200);
        assertThat(TestClient.of(app).get("/greeting").execute().body()).isEqualTo("hello");
    }

    @Test
    void frameworkNeutralRouteContributionsRunAndFeedSchemaMetadata() {
        Aura app = Aura.create().use(new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("contract-routes", "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                context.routes(() -> java.util.List.of(new RouteContribution(
                        "GET", "/contract-route", (BaseHandler) ctx -> ctx.text("ok"),
                        java.util.Map.of("description", "Contract route", "returnType", "string",
                                "parameters", java.util.List.of(
                                        new ApiParameter("q", "string", "query", true, "Search query"))))));
            }
        });

        assertThat(TestClient.of(app).get("/contract-route").query("q", "ready").expect(200).body())
                .isEqualTo("ok");
    }

    @Test
    void routeContributionWithoutHandlerFailsDuringModuleInstallation() {
        Aura app = Aura.create().use(new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("invalid-routes", "1.0.0");
            }
            @Override public void install(ModuleContext context) {
                context.route(new RouteContribution("GET", "/missing-handler"));
            }
        });

        org.assertj.core.api.Assertions.assertThatThrownBy(app::prepareModules)
                .isInstanceOf(io.aura.ModuleException.class)
                .extracting("code").isEqualTo("AURA_ROUTE_HANDLER_MISSING");
    }

    @Test
    void routeContributorIsEvaluatedExactlyOnce() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        Aura app = Aura.create().use(new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("single-evaluation", "1.0.0");
            }
            @Override public void install(ModuleContext context) {
                context.routes(() -> {
                    calls.incrementAndGet();
                    return java.util.List.of(new RouteContribution(
                            "GET", "/once", (BaseHandler) ctx -> ctx.text("ok")));
                });
            }
        });

        assertThat(TestClient.of(app).get("/once").expect(200).body()).isEqualTo("ok");
        assertThat(calls).hasValue(1);
    }
}
