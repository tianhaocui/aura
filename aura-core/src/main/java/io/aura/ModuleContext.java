package io.aura;

import io.aura.web.BaseHandler;
import io.aura.web.BaseRouter;

import java.util.Optional;
import java.util.function.Consumer;

/** Restricted contribution context exposed to one module during installation. */
public interface ModuleContext extends io.aura.contract.CapabilityLookup {
    io.aura.contract.ModuleMetadata currentModule();

    <T> T require(Class<T> type);

    <T> Optional<T> find(Class<T> type);

    void register(Object value);

    void register(String name, Object value);

    void service(Object service);

    void routes(Consumer<BaseRouter> routes);

    void before(BaseHandler handler);

    void after(BaseHandler handler);

    void mcp(McpRouterSpec router);

    void healthCheck(io.aura.contract.HealthCheck healthCheck);

    io.aura.contract.PermissionRegistry permissions();

    String property(String key);

    <T> T config(Class<T> recordType);
}
