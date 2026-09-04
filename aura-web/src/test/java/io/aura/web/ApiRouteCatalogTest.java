package io.aura.web;

import io.aura.contract.ApiParameter;
import io.aura.contract.ApiRoute;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiRouteCatalogTest {
    @Test
    void preservesExplicitLambdaParameterContractForSchemaAdapters() {
        BaseRouter router = new BaseRouter();
        router.post("/orders", ctx -> ctx.text("ok"))
                .param("customerId", "string", "query", true, "Customer identifier")
                .param("order", "Order", "body", true, "Order payload");

        CompiledRoute route = UndertowStarter.compileRoutes(router).get(0);
        ApiRoute description = ApiRouteCatalog.describe(route, "curl");

        assertThat(description.params())
                .extracting(ApiParameter::name)
                .containsExactly("customerId", "order");
        assertThat(description.params().get(0).source()).isEqualTo("query");
        assertThat(description.params().get(1).required()).isTrue();
    }

    @Test
    void routeMetadataDefensivelyCopiesExplicitParameters() {
        var parameter = new ApiParameter("id", "long", "path", true, "Order id");
        var meta = new CompiledRoute.RouteMeta("Order", "Order", null, List.of(parameter));

        assertThat(meta.parameters()).containsExactly(parameter);
    }
}
