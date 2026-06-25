package io.aura;

import io.aura.annotation.Path;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuraSelfRegisterTest {

    public static class NeedsAura {
        public final Aura app;
        public NeedsAura(Aura app) { this.app = app; }
    }

    @Test
    void serviceCanInjectAura() {
        Aura app = Aura.create();
        app.services(NeedsAura.class);

        Map<Class<?>, Object> registry = new LinkedHashMap<>();
        registry.put(Aura.class, app);
        List<Object> resolved = ServiceResolver.resolve(List.of(NeedsAura.class), registry, Map.of());

        assertEquals(1, resolved.size());
        assertInstanceOf(NeedsAura.class, resolved.get(0));
        assertSame(app, ((NeedsAura) resolved.get(0)).app);
    }

    @Path("/test")
    public static class PathService {
        public final Aura app;
        public PathService(Aura app) { this.app = app; }
    }

    @Test
    void pathServiceAddedToServicesList() {
        Aura app = Aura.create();
        app.services(PathService.class);

        assertTrue(app.services().isEmpty());

        Map<Class<?>, Object> registry = new LinkedHashMap<>();
        registry.put(Aura.class, app);
        List<Object> resolved = ServiceResolver.resolve(List.of(PathService.class), registry, Map.of());

        for (Object bean : resolved) {
            if (bean.getClass().isAnnotationPresent(Path.class)) {
                app.services().add(bean);
            }
        }

        assertEquals(1, app.services().size());
        assertInstanceOf(PathService.class, app.services().get(0));
    }
}
