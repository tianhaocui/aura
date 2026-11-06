package io.aura.mcp;

import io.aura.contract.Route;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpIdentityTest {
    @Test
    void authenticatedIdentityIsAvailableToToolHandlers() throws Exception {
        McpRouter router = new McpRouter();
        router.tool(Route.of("GET", "/whoami",
                (McpHandler) context -> context.userId()).describedAs("Return the authenticated identity"));

        Object result = router.invoke("get_whoami", Map.of(), "service-42",
                (user, permission) -> true);

        assertThat(result).isEqualTo("service-42");
    }

    @Test
    void permissionedToolStillFailsClosedWithoutTransportAuthorizer() {
        McpRouter router = new McpRouter();
        router.tool(Route.of("DELETE", "/item", (McpHandler) context -> "deleted")
                .describedAs("Delete an item")
                .requireAuth("items.delete"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                router.invoke("delete_item", Map.of(), "service-42", null))
                .isInstanceOf(SecurityException.class);
    }
}
