package io.aura;

import io.aura.contract.McpRouterSpec;
import io.aura.snapshot.SnapshotOptions;
import io.aura.web.BaseContext;
import io.aura.web.BaseExceptionHandler;
import io.aura.web.BaseHandler;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/** Default runtime adapter used by {@link AuraApp}; the concrete builder stays behind this seam. */
final class DefaultAuraRuntime implements AuraWebRuntime {
    private final Aura delegate;

    DefaultAuraRuntime(Aura delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
        // Make the stable runtime discoverable to modules during install().
        delegate.register(this);
    }

    @Override public io.aura.contract.RuntimeState state() { return delegate.state(); }
    @Override public AuraConfig config() { return delegate.config(); }
    @Override public AuraRuntime use(AuraModule module) { delegate.use(module); return this; }
    @Override public AuraRuntime observe(AuraEventListener listener) { delegate.observe(listener); return this; }
    @Override public AuraRuntime healthCheck(io.aura.contract.HealthCheck check) { delegate.healthCheck(check); return this; }
    @Override public AuraRuntime authorize(java.util.function.BiPredicate<String, String> checker) { delegate.authorize(checker); return this; }
    @Override public AuraRuntime property(String key, String value) { delegate.property(key, value); return this; }
    @Override public void prepareModules() { delegate.prepareModules(); }
    @Override public void start() { delegate.start(); }
    @Override public void start(String[] args) { delegate.start(args); }
    @Override public void stop() { delegate.stop(); }
    @Override public List<io.aura.contract.ModuleMetadata> moduleDescriptors() { return delegate.moduleDescriptors(); }
    @Override public io.aura.contract.PermissionRegistry permissions() { return delegate.permissions(); }
    @Override public <T> java.util.Optional<T> find(Class<T> type) { return delegate.find(type); }

    @Override public int port() { return delegate.port(); }
    @Override public String env() { return delegate.env(); }
    @Override public int workers() { return delegate.workers(); }
    @Override public Consumer<?> routeConfig() { return delegate.routeConfig(); }
    @Override public List<Object> services() { return delegate.services(); }
    @Override public List<RouteEntry> directRoutes() { return delegate.directRoutes(); }
    @Override public List<String> scanPackages() { return delegate.scanPackages(); }
    @Override public List<Class<?>> serviceClasses() { return delegate.serviceClasses(); }
    @Override public String staticFilesPath() { return delegate.staticFilesPath(); }
    @Override public boolean spa() { return delegate.spa(); }
    @Override public String corsOrigin() { return delegate.corsOrigin(); }
    @Override public CorsConfig corsConfig() { return delegate.corsConfig(); }
    @Override public long maxBodySize() { return delegate.maxBodySize(); }
    @Override public long maxUploadSize() { return delegate.maxUploadSize(); }
    @Override public int shutdownTimeout() { return delegate.shutdownTimeout(); }
    @Override public boolean accessLog() { return delegate.accessLog(); }
    @Override public String accessLogFormat() { return delegate.accessLogFormat(); }
    @Override public int requestTimeout() { return delegate.requestTimeout(); }
    @Override public boolean gzip() { return delegate.gzip(); }
    @Override public int gzipMinSize() { return delegate.gzipMinSize(); }
    @Override public JsonConfig jsonConfig() { return delegate.jsonConfig(); }
    @Override public int mcpPort() { return delegate.mcpPort(); }
    @Override public PrintStream mcpStdout() { return delegate.mcpStdout(); }
    @Override public McpRouterSpec mcpRouter() { return delegate.mcpRouter(); }
    @Override public Set<String> allowedHosts() { return delegate.allowedHosts(); }
    @Override public boolean securityHeaders() { return delegate.securityHeaders(); }
    @Override public boolean openApiEnabled() { return delegate.openApiEnabled(); }
    @Override public int concurrencyLimit() { return delegate.concurrencyLimit(); }
    @Override public int rateLimitPerSecond() { return delegate.rateLimitPerSecond(); }
    @Override public String publicOrigin() { return delegate.publicOrigin(); }
    @Override public boolean isShuttingDown() { return delegate.isShuttingDown(); }
    @Override public void emit(AuraEvent event) { delegate.emit(event); }
    @Override public String prop(String key) { return delegate.prop(key); }
    @Override public <T> T getBean(Class<T> type) { return delegate.getBean(type); }
    @Override public Function<BaseContext, String> authFunction() { return delegate.authFunction(); }
    @Override public java.util.function.BiPredicate<String, String> permissionChecker() { return delegate.permissionChecker(); }
    @Override public Map<Class<? extends Exception>, BaseExceptionHandler<?>> exceptionHandlers() { return delegate.exceptionHandlers(); }
    @Override public List<BaseHandler> beforeHandlers() { return delegate.beforeHandlers(); }
    @Override public List<BaseHandler> afterHandlers() { return delegate.afterHandlers(); }
    @Override public Function<Object, Object> resultWrapper() { return delegate.resultWrapper(); }
    @Override public SnapshotOptions snapshotOptions() { return delegate.snapshotOptions(); }
}
