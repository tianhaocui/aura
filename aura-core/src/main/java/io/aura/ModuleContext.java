package io.aura;

import java.util.Optional;

/** Restricted contribution context exposed to one module during installation. */
public interface ModuleContext extends io.aura.contract.CapabilityLookup {
    io.aura.contract.ModuleMetadata currentModule();

    <T> T require(Class<T> type);

    <T> Optional<T> find(Class<T> type);

    void register(Object value);

    void register(String name, Object value);

    void service(Object service);

    /** Adds framework-neutral route contributions for an optional transport adapter. */
    default void routes(io.aura.contract.RouteContributor routes) {
        if (routes == null) {
            throw new IllegalArgumentException("route contributor must return routes");
        }
        java.util.Collection<io.aura.contract.RouteContribution> contributions = routes.routes();
        if (contributions == null) {
            throw new IllegalArgumentException("route contributor must return routes");
        }
        contributions.forEach(this::route);
    }

    void route(io.aura.contract.RouteContribution route);

    /** Adds one WebSocket endpoint owned by the current business module. */
    void webSocket(String path, java.util.function.Consumer<io.aura.web.WsHandler> configure);

    /** Adds middleware applied before every assembled HTTP route and WebSocket handshake. */
    void before(io.aura.web.BaseHandler handler);

    /** Adds middleware applied after every assembled HTTP route. */
    void after(io.aura.web.BaseHandler handler);

    /** Registers structured handling for one application exception type. */
    <T extends Exception> void exception(Class<T> type, io.aura.web.BaseExceptionHandler<T> handler);

    /** Installs the single application-wide successful return-value wrapper. */
    void resultWrapper(java.util.function.Function<Object, Object> wrapper);

    /** Replaces the default global admission controls with one module-owned policy. */
    void admissionPolicy(io.aura.web.AdmissionPolicy policy);

    void healthCheck(io.aura.contract.HealthCheck healthCheck);

    io.aura.contract.PermissionRegistry permissions();

    String property(String key);

    <T> T config(Class<T> recordType);
}
