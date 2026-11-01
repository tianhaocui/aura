package io.aura.contract;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteTest {

    @Test
    void minimalFactoryUppercasesMethodAndTrimsPath() {
        Route r = Route.of("get", "  /user/{id} ", new Object());
        assertThat(r.method()).isEqualTo("GET");
        assertThat(r.path()).isEqualTo("/user/{id}");
        assertThat(r.params()).isEmpty();
        assertThat(r.authenticated()).isFalse();
    }

    @Test
    void nullHandlerFailsFast() {
        assertThatThrownBy(() -> new Route("GET", "/x", null,
                List.of(), null, Map.of(), null, false, null, false))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void pathMustStartWithSlash() {
        assertThatThrownBy(() -> Route.of("GET", "user", new Object()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start with");
    }

    @Test
    void permissionImpliesAuthenticated() {
        Route r = Route.of("GET", "/x", new Object()).requireAuth("admin.write");
        assertThat(r.authenticated()).isTrue();
        assertThat(r.permission()).isEqualTo("admin.write");
    }

    @Test
    void blankPermissionCollapsesToNull() {
        Route r = new Route("GET", "/x", new Object(), List.of(), null, Map.of(),
                null, true, "  ", false);
        assertThat(r.permission()).isNull();
    }

    @Test
    void fromContributionReadsStringMetadataIntoTypedFields() {
        RouteContribution c = new RouteContribution("POST", "/items", new Object(),
                Map.of("description", "create item",
                        "returnType", "Item",
                        "streaming", true,
                        RouteContribution.AUTHENTICATED, true));
        Route r = Route.fromContribution(c);
        assertThat(r.description()).isEqualTo("create item");
        assertThat(r.returnType()).isEqualTo("Item");
        assertThat(r.streaming()).isTrue();
        assertThat(r.authenticated()).isTrue();
    }

    @Test
    void fromContributionRejectsBadMetadataTypes() {
        RouteContribution c = new RouteContribution("POST", "/items", new Object(),
                Map.of("responseSchema", "not-a-map"));
        assertThatThrownBy(() -> Route.fromContribution(c))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a Map");
    }

    @Test
    void toApiRouteProjectsAllFieldsExceptHandler() {
        Route r = Route.of("GET", "/u/{id}", new Object())
                .withParams(List.of(new ApiParameter("id", "int", "path", true, "user id")))
                .withReturnType("User")
                .describedAs("lookup user");
        ApiRoute api = r.toApiRoute();
        assertThat(api.method()).isEqualTo("GET");
        assertThat(api.path()).isEqualTo("/u/{id}");
        assertThat(api.description()).isEqualTo("lookup user");
        assertThat(api.returnType()).isEqualTo("User");
        assertThat(api.params()).hasSize(1);
    }

    @Test
    void apiRouteFromRoundTripsViaRoute() {
        Route original = Route.of("PUT", "/orders/{id}", new Object())
                .requireAuth("orders.write")
                .describedAs("update order");
        ApiRoute projected = ApiRoute.from(original);
        assertThat(projected.authenticated()).isTrue();
        assertThat(projected.permission()).isEqualTo("orders.write");
        assertThat(projected.description()).isEqualTo("update order");
    }

    @Test
    void contributionToRouteMatchesStaticFactory() {
        RouteContribution c = new RouteContribution("GET", "/x", new Object());
        assertThat(c.toRoute()).isEqualTo(Route.fromContribution(c));
    }
}
