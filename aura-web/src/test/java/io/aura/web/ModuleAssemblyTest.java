package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraRuntime;
import io.aura.AuraWebRuntime;
import io.aura.AuraConfig;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ApiParameter;
import io.aura.contract.ModuleMetadata;
import io.aura.contract.Route;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleAssemblyTest {
    @Test
    void moduleRoutesAreAvailableToInMemoryTestClient() {
        AuraApp app = AuraApp.create().use(new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("greeting", "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                context.route(Route.of("GET", "/greeting",
                        (BaseHandler) ctx -> ctx.text("hello")));
            }
        });

        assertThat(TestClient.of(app).get("/greeting").execute().status()).isEqualTo(200);
        assertThat(TestClient.of(app).get("/greeting").execute().body()).isEqualTo("hello");
    }

    @Test
    void frameworkNeutralRoutesRunAndFeedSchemaMetadata() {
        AuraApp app = AuraApp.create().use(new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("contract-routes", "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                context.route(Route.of("GET", "/contract-route", (BaseHandler) ctx -> ctx.text("ok"))
                        .withParams(java.util.List.of(
                                new ApiParameter("q", "string", "query", true, "Search query")))
                        .withReturnType("string")
                        .describedAs("Contract route"));
            }
        });

        assertThat(TestClient.of(app).get("/contract-route").query("q", "ready").expect(200).body())
                .isEqualTo("ok");
    }

    @Test
    void testClientAcceptsTheStableRuntimeSeam() {
        AuraApp app = AuraApp.create().use(new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("runtime-client", "1.0.0");
            }
            @Override public void install(ModuleContext context) {
                context.route(Route.of("GET", "/runtime-client", (BaseHandler) ctx -> ctx.text("ok")));
            }
        });

        AuraRuntime runtime = app.runtimeView();
        assertThat(runtime).isNotSameAs(app);
        assertThat(TestClient.of(runtime).get("/runtime-client").expect(200).body()).isEqualTo("ok");
    }

    @Test
    void testClientDoesNotRequireTheConcreteAuraImplementation() {
        AuraApp app = AuraApp.create().use(new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("runtime-proxy", "1.0.0");
            }
            @Override public void install(ModuleContext context) {
                context.route(Route.of(
                        "GET", "/runtime-proxy", (BaseHandler) ctx -> ctx.text("proxy-ok")));
            }
        });

        AuraRuntime delegate = app.runtimeView();
        AuraWebRuntime runtime = (AuraWebRuntime) java.lang.reflect.Proxy.newProxyInstance(
                AuraWebRuntime.class.getClassLoader(),
                new Class<?>[]{AuraWebRuntime.class},
                (proxy, method, args) -> method.invoke(delegate, args));

        assertThat(TestClient.of((AuraRuntime) runtime)
                .get("/runtime-proxy").expect(200).body()).isEqualTo("proxy-ok");
    }

    @Test
    void auraAppStartsTheIndependentRuntimeThroughTheWebAdapter() {
        AuraApp app = AuraApp.create(new AuraConfig().port(0)).use(new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("runtime-server", "1.0.0");
            }
            @Override public void install(ModuleContext context) {
                context.route(Route.of(
                        "GET", "/runtime-server", (BaseHandler) ctx -> ctx.text("running")));
            }
        });

        try {
            app.start();
            assertThat(app.state()).isEqualTo(io.aura.contract.RuntimeState.RUNNING);
        } finally {
            app.close();
        }
        assertThat(app.state()).isEqualTo(io.aura.contract.RuntimeState.STOPPED);
    }

    @Test
    void modularRuntimeAuthenticationFlowsThroughTestClientContext() {
        AuraApp app = AuraApp.create()
                .authenticate(ctx -> "user-42")
                .use(new AuraModule() {
                    @Override public ModuleMetadata descriptor() {
                        return ModuleMetadata.of("authenticated-route", "1.0.0");
                    }
                    @Override public void install(ModuleContext context) {
                        context.route(Route.of(
                                "GET", "/authenticated",
                                (BaseHandler) ctx -> {
                                    String userId = ctx.app().authFunction().apply(ctx);
                                    ctx.set("_userId", userId);
                                    ctx.text(ctx.userId());
                                }));
                    }
                });

        assertThat(TestClient.of(app).get("/authenticated").expect(200).body()).isEqualTo("user-42");
    }
}
