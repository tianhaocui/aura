package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;
import io.aura.contract.Route;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pre-route guard chain is shared by {@code UndertowStarter} and {@code TestClient}, so these
 * millisecond assertions pin the same host, CSRF, CORS, and admission decisions that
 * {@link DeploymentSecurityTest} proves over a real socket.
 */
class GuardChainTest {

    private static final Route GET_OK =
            Route.of("GET", "/ok", (BaseHandler) ctx -> ctx.text("ok"));

    private static final Route POST_ORDERS =
            Route.of("POST", "/orders", (BaseHandler) ctx -> ctx.text("placed"));

    private static TestClient client(AuraConfig config, Route... routes) {
        return TestClient.of(TestApplications.app(config, "guard-chain", routes));
    }

    // --- security headers ---

    @Test
    void securityHeaders_appliedByDefault() {
        TestClient.Response resp = client(new AuraConfig(), GET_OK).get("/ok").execute();

        resp.expect(200);
        assertThat(resp.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(resp.header("X-Frame-Options")).isEqualTo("DENY");
        assertThat(resp.header("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(resp.header("Strict-Transport-Security")).isNull();
    }

    @Test
    void securityHeaders_absentWhenDisabled() {
        TestClient.Response resp = client(new AuraConfig().securityHeaders(false), GET_OK)
                .get("/ok").execute();

        resp.expect(200);
        assertThat(resp.header("X-Content-Type-Options")).isNull();
        assertThat(resp.header("X-Frame-Options")).isNull();
    }

    @Test
    void securityHeaders_addHstsOnlyForTlsRequests() {
        TestClient.Response resp = client(new AuraConfig(), GET_OK).get("/ok").secure(true).execute();

        assertThat(resp.header("Strict-Transport-Security")).isEqualTo("max-age=31536000; includeSubDomains");
    }

    @Test
    void securityHeaders_reachResponsesNoRouteServes() {
        TestClient.Response resp = client(new AuraConfig()).get("/missing").execute();

        resp.expect(404);
        assertThat(resp.header("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    // --- host allow-list ---

    @Test
    void allowedHosts_rejectsForeignHostAndAcceptsListedOne() {
        TestClient untrusted = client(new AuraConfig().allowedHosts("api.example"), GET_OK);

        untrusted.get("/ok").header("Host", "evil.example").expect(421)
                .bodyContains("Misdirected Request");
        untrusted.get("/ok").header("Host", "API.Example").expect(200);
    }

    @Test
    void publicOrigin_definesTheOnlyAllowedHost() {
        TestClient.Response resp = client(new AuraConfig().publicOrigin("https://api.example"))
                .get("/ok").header("Host", "other.example").execute();

        resp.expect(421);
    }

    // --- CSRF ---

    @Test
    void csrf_rejectsUnsafeRequestWithoutEitherDefense() {
        TestClient.Response resp = client(csrfConfig(), POST_ORDERS).post("/orders").execute();

        resp.expect(403);
        assertThat(resp.body()).contains("CSRF validation failed");
    }

    @Test
    void csrf_acceptsMatchingHeaderAndCookie() {
        TestClient.Response resp = client(csrfConfig(), POST_ORDERS).post("/orders")
                .header("X-CSRF-Token", "t0k3n")
                .cookie("csrf_token", "t0k3n")
                .execute();

        resp.expect(200).bodyContains("placed");
    }

    @Test
    void csrf_rejectsMismatchedHeaderAndCookie() {
        TestClient.Response resp = client(csrfConfig(), POST_ORDERS).post("/orders")
                .header("X-CSRF-Token", "t0k3n")
                .cookie("csrf_token", "other")
                .execute();

        resp.expect(403);
    }

    @Test
    void csrf_leavesSafeMethodsAlone() {
        client(csrfConfig(), GET_OK).get("/ok").expect(200);
    }

    @Test
    void csrf_trustedProxyForwardsTheBrowserOrigin() {
        TestClient.Response trusted = client(new AuraConfig().env("prod").trustedProxies("127.0.0.1")
                .property("aura.csrf.enabled", "true"), POST_ORDERS)
                .post("/orders").remoteAddr("127.0.0.1")
                .header("Origin", "https://api.example")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "api.example")
                .execute();
        assertThat(trusted.status()).isEqualTo(200);

        TestClient.Response untrusted = client(new AuraConfig().env("prod")
                .property("aura.csrf.enabled", "true"), POST_ORDERS)
                .post("/orders").remoteAddr("198.51.100.9")
                .header("Origin", "https://api.example")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "api.example")
                .execute();
        assertThat(untrusted.status()).isEqualTo(403);
    }

    @Test
    void csrf_isImpliedByAPublicOriginOutsideDev() {
        TestClient.Response resp = client(new AuraConfig().env("prod").publicOrigin("https://api.example"),
                POST_ORDERS).post("/orders").header("Host", "api.example").execute();

        resp.expect(403);
    }

    private static AuraConfig csrfConfig() {
        return new AuraConfig().property("aura.csrf.enabled", "true");
    }

    // --- CORS ---

    @Test
    void cors_answersPreflightWithoutReachingARoute() {
        TestClient.Response resp = client(new AuraConfig().cors("https://app.example"))
                .options("/ok").execute();

        resp.expect(204);
        assertThat(resp.header("Access-Control-Allow-Origin")).isEqualTo("https://app.example");
        assertThat(resp.header("Access-Control-Allow-Methods")).contains("DELETE");
    }

    @Test
    void cors_reflectsAllowedOriginAndVariesOnTheRequestHeader() {
        TestClient allowed = client(new AuraConfig().cors(c -> c.origins("https://app.example")), GET_OK);

        allowed.get("/ok").header("Origin", "https://app.example").execute()
                .expectHeader("Access-Control-Allow-Origin", "https://app.example");
        assertThat(allowed.get("/ok").header("Origin", "https://evil.example").execute()
                .header("Access-Control-Allow-Origin")).isNull();
        assertThat(allowed.get("/ok").header("Origin", "https://evil.example").execute()
                .header("Vary")).contains("Origin");
    }

    @Test
    void cors_wildcardReflectsWithoutCredentials() {
        TestClient.Response resp = client(new AuraConfig().cors(true), GET_OK)
                .get("/ok").header("Origin", "https://any.example").execute();

        resp.expect(200);
        assertThat(resp.header("Access-Control-Allow-Origin")).isEqualTo("*");
        assertThat(resp.header("Access-Control-Allow-Credentials")).isNull();
        assertThat(resp.header("Vary")).isNull();
    }

    @Test
    void cors_refusesToConfigureWildcardTogetherWithCredentials() {
        assertThatThrownBy(() -> new AuraConfig().cors(c -> c.origins("*").credentials(true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wildcard");
    }

    @Test
    void cors_disabledLeavesPreflightToTheRouter() {
        client(new AuraConfig(), GET_OK).options("/ok").expect(404);
    }

    // --- admission ---

    @Test
    void rateLimit_rejectsRepeatedRequestsFromOnePeerWithinTheWindow() {
        TestClient limited = client(new AuraConfig().rateLimitPerSecond(1), GET_OK);

        limited.get("/ok").remoteAddr("203.0.113.7").expect(200);
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            TestClient.Response resp = limited.get("/ok").remoteAddr("203.0.113.7").execute();
            statuses.add(resp.status());
            if (resp.status() == 429) assertThat(resp.header("Retry-After")).isEqualTo("1");
        }

        assertThat(statuses).contains(429);
        assertThat(Collections.frequency(statuses, 200)).isLessThanOrEqualTo(2);
    }

    @Test
    void rateLimit_ignoresForwardedForUntilTheProxyIsTrusted() {
        TestClient untrusted = client(new AuraConfig().rateLimitPerSecond(1), GET_OK);
        List<Integer> statuses = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            statuses.add(untrusted.get("/ok").header("X-Forwarded-For", "203.0.113." + i)
                    .execute().status());
        }
        // One anonymous bucket, so six different browsers still share a limit.
        assertThat(Collections.frequency(statuses, 200)).isLessThanOrEqualTo(2);

        TestClient trusted = client(new AuraConfig().rateLimitPerSecond(1).trustedProxies("127.0.0.1"),
                GET_OK);
        for (int i = 1; i <= 6; i++) {
            trusted.get("/ok").remoteAddr("127.0.0.1")
                    .header("X-Forwarded-For", "203.0.113." + i).expect(200);
        }
    }

    @Test
    void admissionPolicy_usesRouteTemplatesAndReportsItsCode() {
        StandardAdmissionPolicy policy = StandardAdmissionPolicy.builder()
                .routeRate("/orders/{id}", 1)
                .build();
        AuraApp app = AuraApp.create(new AuraConfig()).use(new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("admission", "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                context.admissionPolicy(policy);
                context.route(Route.of("GET", "/orders/{id}",
                        (BaseHandler) ctx -> ctx.text(ctx.path("id"))));
            }
        });
        TestClient client = TestClient.of(app);

        client.get("/orders/1").header("X-API-Key", "key-1").expect(200);
        List<TestClient.Response> responses = new ArrayList<>();
        for (int i = 2; i < 8; i++) {
            responses.add(client.get("/orders/" + i).header("X-API-Key", "key-" + i).execute());
        }

        TestClient.Response blocked = responses.stream()
                .filter(response -> response.status() == 429)
                .findFirst()
                .orElseThrow(() -> new AssertionError("route template never limited /orders/{id}"));
        assertThat(blocked.body()).contains(StandardAdmissionPolicy.RATE_LIMITED);
        assertThat(policy.rateRejections()).isGreaterThan(0);
    }

    @Test
    void admissionPolicy_failureClosesAndKeepsItsErrorCode() {
        AuraApp app = AuraApp.create(new AuraConfig()).use(new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("broken-admission", "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                context.admissionPolicy(request -> {
                    throw new IllegalStateException("authorizer offline");
                });
                context.route(Route.of("GET", "/ok", (BaseHandler) ctx -> ctx.text("ok")));
            }
        });

        TestClient.of(app).get("/ok").execute()
                .expect(503)
                .bodyContains("AURA_ADMISSION_POLICY_ERROR");
    }
}
