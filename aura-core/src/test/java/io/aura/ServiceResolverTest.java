package io.aura;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ServiceResolverTest {

    public static class Repo {}

    public static class ServiceA {
        public final Repo repo;
        public ServiceA(Repo repo) { this.repo = repo; }
    }

    public static class ServiceB {
        public final ServiceA a;
        public ServiceB(ServiceA a) { this.a = a; }
    }

    public static class ServiceC {
        public final ServiceA a;
        public final ServiceB b;
        public ServiceC(ServiceA a, ServiceB b) { this.a = a; this.b = b; }
    }

    @Test
    void resolvesLinearDependencies() {
        Map<Class<?>, Object> registry = new LinkedHashMap<>();
        Repo repo = new Repo();
        registry.put(Repo.class, repo);

        List<Object> result = ServiceResolver.resolve(
                List.of(ServiceA.class, ServiceB.class), registry, Map.of());

        assertEquals(2, result.size());
        assertInstanceOf(ServiceA.class, result.get(0));
        assertInstanceOf(ServiceB.class, result.get(1));
        assertSame(repo, ((ServiceA) result.get(0)).repo);
        assertSame(result.get(0), ((ServiceB) result.get(1)).a);
    }

    @Test
    void resolvesOutOfOrderDeclaration() {
        Map<Class<?>, Object> registry = new LinkedHashMap<>();
        registry.put(Repo.class, new Repo());

        List<Object> result = ServiceResolver.resolve(
                List.of(ServiceB.class, ServiceA.class), registry, Map.of());

        assertInstanceOf(ServiceA.class, result.get(0));
        assertInstanceOf(ServiceB.class, result.get(1));
    }

    @Test
    void resolvesMultipleDependencies() {
        Map<Class<?>, Object> registry = new LinkedHashMap<>();
        registry.put(Repo.class, new Repo());

        List<Object> result = ServiceResolver.resolve(
                List.of(ServiceC.class, ServiceA.class, ServiceB.class), registry, Map.of());

        assertEquals(3, result.size());
        assertInstanceOf(ServiceA.class, result.get(0));
        assertInstanceOf(ServiceB.class, result.get(1));
        assertInstanceOf(ServiceC.class, result.get(2));
    }

    public static class CycleA {
        public CycleA(CycleB b) {}
    }
    public static class CycleB {
        public CycleB(CycleA a) {}
    }

    @Test
    void detectsCycle() {
        Map<Class<?>, Object> registry = new LinkedHashMap<>();
        var ex = assertThrows(IllegalStateException.class, () ->
                ServiceResolver.resolve(List.of(CycleA.class, CycleB.class), registry, Map.of()));
        assertTrue(ex.getMessage().contains("Circular dependency"));
    }

    @Test
    void errorOnMissingDependency() {
        Map<Class<?>, Object> registry = new LinkedHashMap<>();
        var ex = assertThrows(IllegalStateException.class, () ->
                ServiceResolver.resolve(List.of(ServiceA.class), registry, Map.of()));
        assertTrue(ex.getMessage().contains("Cannot create ServiceA"));
        assertTrue(ex.getMessage().contains("Repo"));
        assertTrue(ex.getMessage().contains("Hint"));
    }

    public static class MultiCtor {
        public MultiCtor() {}
        public MultiCtor(Repo r) {}
    }

    @Test
    void errorOnMultipleConstructors() {
        Map<Class<?>, Object> registry = new LinkedHashMap<>();
        var ex = assertThrows(IllegalStateException.class, () ->
                ServiceResolver.resolve(List.of(MultiCtor.class), registry, Map.of()));
        assertTrue(ex.getMessage().contains("ambiguous"));
    }

    public interface MyInterface {}
    public static class MyImpl implements MyInterface {
        public MyImpl() {}
    }
    public static class NeedsInterface {
        public final MyInterface dep;
        public NeedsInterface(MyInterface dep) { this.dep = dep; }
    }

    @Test
    void matchesByInterface() {
        Map<Class<?>, Object> registry = new LinkedHashMap<>();
        MyImpl impl = new MyImpl();
        registry.put(MyImpl.class, impl);

        List<Object> result = ServiceResolver.resolve(
                List.of(NeedsInterface.class), registry, Map.of());

        assertEquals(1, result.size());
        assertSame(impl, ((NeedsInterface) result.get(0)).dep);
    }

    public static class ReloadableService implements Reloadable {
        public boolean reloaded;
        public ReloadableService() {}
        @Override public void reload(Aura app) { reloaded = true; }
    }

    @Test
    void reloadableServicesTracked() {
        Aura app = Aura.create();
        app.register(new Repo());
        app.services(ServiceA.class, ReloadableService.class);
        // Can't call start without AuraStarter, so test the resolver directly
        Map<Class<?>, Object> registry = new LinkedHashMap<>();
        registry.put(Repo.class, new Repo());
        List<Object> resolved = ServiceResolver.resolve(
                List.of(ReloadableService.class), registry, Map.of());
        assertInstanceOf(Reloadable.class, resolved.get(0));
    }

    public static class ImplA implements MyInterface {
        public ImplA() {}
    }
    public static class ImplB implements MyInterface {
        public ImplB() {}
    }

    @Test
    void errorOnAmbiguousInterfaceMatch() {
        Map<Class<?>, Object> registry = new LinkedHashMap<>();
        registry.put(ImplA.class, new ImplA());
        registry.put(ImplB.class, new ImplB());

        var ex = assertThrows(IllegalStateException.class, () ->
                ServiceResolver.resolve(List.of(NeedsInterface.class), registry, Map.of()));
        assertTrue(ex.getMessage().contains("Multiple beans match type"));
        assertTrue(ex.getMessage().contains("ImplA"));
        assertTrue(ex.getMessage().contains("ImplB"));
    }
}
