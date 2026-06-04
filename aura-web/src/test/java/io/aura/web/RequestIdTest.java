package io.aura.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class RequestIdTest {

    @Test
    void generateShortId_returns8chars() throws Exception {
        var method = UndertowStarter.class.getDeclaredMethod("generateShortId");
        method.setAccessible(true);
        String id = (String) method.invoke(null);
        assertThat(id).hasSize(8);
        assertThat(id).matches("[0-9a-f]+");
    }

    @Test
    void generateShortId_unique() throws Exception {
        var method = UndertowStarter.class.getDeclaredMethod("generateShortId");
        method.setAccessible(true);
        String id1 = (String) method.invoke(null);
        String id2 = (String) method.invoke(null);
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void requestId_exposedOnContext() {
        var ctx = new MockContext(
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), null, null);
        assertThat(ctx.requestId()).isNull();
    }
}
