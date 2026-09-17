package io.aura.web;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class ResultWrapperTest {

    @Test
    void modularRuntime_serializesReturnValueWithoutLegacyGlobalWrapper() {
        Router router = new Router();
        router.addRoute("GET", "/data",
                new LambdaHandler((Supplier<Map<String, String>>) () -> Map.of("key", "value")));

        var resp = TestApplications.client(router).get("/data").execute();

        assertThat(resp.body()).contains("\"key\"");
        assertThat(resp.body()).doesNotContain("\"code\"");
    }

    @Test
    void sharedRouteResponse_preservesUnwrappedAndCommittedResponseSemantics() {
        MockContext context = new MockContext(
                Map.of(), Map.of(), Map.of(), null, TestApplications.runtime());

        RouteResponse.write(context, null);
        assertThat(context.responseBody).isNull();

        context.text("manual");
        RouteResponse.write(context, Map.of("overwritten", true));
        assertThat(context.responseBody).isEqualTo("manual");
    }
}
