package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;
import io.aura.contract.RouteContribution;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleWebContributionsTest {

    @Test
    void middlewareExceptionsAndResultWrapperMatchTestClientSemantics() {
        AuraApp app = AuraApp.create().use(new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("web-policy", "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                context.before(request -> request.set("before", "applied"));
                context.after(request -> request.header("X-After", "applied"));
                context.exception(IllegalStateException.class,
                        (error, request) -> request.status(409).json(Map.of(
                                "code", "ORDER_STATE_INVALID", "error", "invalid state")));
                context.resultWrapper(value -> Map.of("data", value));
                context.route(new RouteContribution("GET", "/middleware", (BaseHandler) request ->
                        request.text(request.get("before", String.class))));
                context.route(new RouteContribution("GET", "/failure", (BaseHandler) request -> {
                    throw new IllegalStateException("secret internal state");
                }));
                context.route(new RouteContribution(
                        "GET", "/wrapped", (Supplier<String>) () -> "value"));
            }
        }).prepare();

        TestClient client = TestClient.of(app);
        TestClient.Response middleware = client.get("/middleware").execute();
        assertThat(middleware.body()).isEqualTo("applied");
        assertThat(middleware.header("X-After")).isEqualTo("applied");
        assertThat(client.get("/failure").execute().status()).isEqualTo(409);
        assertThat(client.get("/failure").execute().body()).contains("ORDER_STATE_INVALID")
                .doesNotContain("secret internal state");
        assertThat(client.get("/wrapped").execute().body()).contains("\"data\":\"value\"");
    }

    @Test
    void rejectsMultipleApplicationResultWrappers() {
        AuraModule first = wrapperModule("first");
        AuraModule second = wrapperModule("second");

        assertThatThrownBy(() -> AuraApp.create().use(first).use(second).prepare())
                .isInstanceOf(io.aura.ModuleException.class)
                .extracting("code").isEqualTo("AURA_RESULT_WRAPPER_DUPLICATE");
    }

    private static AuraModule wrapperModule(String id) {
        return new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of(id, "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                context.resultWrapper(value -> value);
            }
        };
    }
}
