package io.aura.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpIdentityTest {
    @Test
    void authenticatedIdentityIsAvailableToToolHandlers() throws Exception {
        McpRouter router = new McpRouter();
        router.tool("whoami", "Return the authenticated identity")
                .handler(context -> context.userId());

        Object result = router.invoke("whoami", Map.of(), "service-42",
                (user, permission) -> true);

        assertThat(result).isEqualTo("service-42");
    }

    @Test
    void permissionedToolStillFailsClosedWithoutTransportAuthorizer() {
        McpRouter router = new McpRouter();
        router.tool("delete", "Delete an item")
                .permission("items.delete")
                .handler(context -> "deleted");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                router.invoke("delete", Map.of(), "service-42", null))
                .isInstanceOf(SecurityException.class);
    }
}
