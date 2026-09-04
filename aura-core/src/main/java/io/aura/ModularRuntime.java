package io.aura;

import io.aura.contract.McpRouterSpec;
import io.aura.contract.ModuleMetadata;
import io.aura.contract.PermissionRegistry;
import io.aura.contract.RouteContribution;
import io.aura.contract.RuntimeState;
import io.aura.snapshot.SnapshotOptions;
import io.aura.web.BaseContext;
import io.aura.web.BaseExceptionHandler;
import io.aura.web.BaseHandler;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Independent 1.0 runtime used by AuraApp. It owns only modular contributions
 * and lifecycle state; the legacy Aura builder is not part of this runtime.
 */
final class ModularRuntime implements AuraWebRuntime {
    private final AuraConfig config;
    private final Map<String, String> properties = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> registry = new ConcurrentHashMap<>();
    private final Map<String, Object> namedRegistry = new ConcurrentHashMap<>();
    private final List<Object> services = new CopyOnWriteArrayList<>();
    private final List<RouteEntry> directRoutes = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> closeables = new CopyOnWriteArrayList<>();
    private final List<io.aura.contract.HealthCheck> healthChecks = new CopyOnWriteArrayList<>();
    private final List<AuraEventListener> eventListeners = new CopyOnWriteArrayList<>();
    private final List<BaseHandler> beforeHandlers = new CopyOnWriteArrayList<>();
    private final List<BaseHandler> afterHandlers = new CopyOnWriteArrayList<>();
    private final Map<Class<? extends Exception>, BaseExceptionHandler<?>> exceptionHandlers = new ConcurrentHashMap<>();
    private final PermissionRegistry permissions = new PermissionRegistry();
    private final SnapshotOptions snapshotOptions = new SnapshotOptions();
    private final JsonConfig jsonConfig = new JsonConfig();
    private final ModuleManager moduleManager = new ModuleManager();
    private final AtomicReference<RuntimeState> state = new AtomicReference<>(RuntimeState.NEW);
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private BiPredicate<String, String> permissionChecker;
    private Function<BaseContext, String> authFunction;
    private Function<Object, Object> resultWrapper;
    private String corsOrigin;
    private CorsConfig corsConfig;
    private String staticFilesPath;
    private boolean spa;
    private boolean accessLog;
    private String accessLogFormat;
    private boolean gzip;
    private int gzipMinSize = 1024;
    private int mcpPort = -1;
    private PrintStream mcpStdout;
    private McpRouterSpec mcpRouter;
    private AuraStarter starter;
    private McpStarter mcpStarter;
    private volatile Thread keepAliveThread;

    ModularRuntime(AuraConfig source) {
        this.config = source == null ? new AuraConfig() : source;
        this.config.validate();
        registry.put(ModularRuntime.class, this);
    }

    @Override public RuntimeState state() { return state.get(); }
    @Override public AuraConfig config() { return config; }
    @Override public AuraRuntime use(AuraModule module) { moduleManager.add(module); return this; }
    @Override public AuraRuntime observe(AuraEventListener listener) {
        if (listener == null) throw new IllegalArgumentException("event listener must not be null");
        eventListeners.add(listener);
        return this;
    }
    @Override public AuraRuntime healthCheck(io.aura.contract.HealthCheck check) {
        if (check != null) healthChecks.add(check);
        return this;
    }
    @Override public AuraRuntime authorize(BiPredicate<String, String> checker) {
        permissionChecker = checker;
        return this;
    }
    @Override public AuraRuntime authenticate(Function<BaseContext, String> authenticator) {
        authFunction = authenticator;
        return this;
    }
    @Override public AuraRuntime property(String key, String value) {
        if (key == null || !key.startsWith("aura.")) {
            throw new IllegalArgumentException("AuraApp properties must use the aura.<module>.* namespace");
        }
        if (value == null) properties.remove(key); else properties.put(key, value);
        return this;
    }

    @Override public void prepareModules() {
        moduleManager.install(this::moduleContext, false);
    }

    @Override public void start(String[] args) {
        boolean stdio = false;
        if (args != null) for (String arg : args) {
            if (arg == null) continue;
            if (arg.startsWith("--config=")) throw new IllegalArgumentException("External config is not supported by AuraApp yet");
            if ("--mcp-stdio".equals(arg)) stdio = true;
            if ("--dev".equals(arg)) ReloadState.devMode = true;
            if (arg.startsWith("--") && arg.contains("=")) {
                int equals = arg.indexOf('=');
                property("aura." + arg.substring(2, equals), arg.substring(equals + 1));
            }
        }
        if (stdio) {
            mcpPort = -2;
            mcpStdout = System.out;
            System.setOut(System.err);
        }
        start();
    }

    @Override public void start() {
        RuntimeState current = state.get();
        if (current == RuntimeState.RUNNING || current == RuntimeState.STARTING) return;
        if (current == RuntimeState.STOPPING || current == RuntimeState.STOPPED) {
            throw new IllegalStateException("Aura runtime cannot be started after stopping");
        }
        if (!state.compareAndSet(RuntimeState.NEW, RuntimeState.STARTING)) return;
        try {
            config.validate();
            moduleManager.install(this::moduleContext, true);
            StartupCheck.checkParameterNames(services);
            starter = java.util.ServiceLoader.load(AuraStarter.class).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No AuraStarter found. Add aura-web to your dependencies."));
            starter.start(this);
            state.set(RuntimeState.RUNNING);
            if (mcpPort >= 0 || mcpPort == -2) {
                mcpStarter = java.util.ServiceLoader.load(McpStarter.class).findFirst().orElse(null);
                if (mcpStarter == null) {
                    throw new IllegalStateException("MCP is enabled but no McpStarter was found. Add aura-mcp.");
                }
                if (mcpPort == -2) mcpStarter.startStdio(this); else mcpStarter.start(this);
            }
            keepAliveThread = new Thread(() -> {
                try { new java.util.concurrent.CountDownLatch(1).await(); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }, "aura-main");
            keepAliveThread.setDaemon(false);
            keepAliveThread.start();
        } catch (RuntimeException | Error failure) {
            failStart(failure);
            throw failure;
        }
    }

    @Override public void stop() {
        RuntimeState current = state.get();
        if (current == RuntimeState.STOPPED || current == RuntimeState.STOPPING) return;
        if (!state.compareAndSet(current, RuntimeState.STOPPING)) return;
        shuttingDown.set(true);
        if (keepAliveThread != null) keepAliveThread.interrupt();
        if (mcpStarter != null) { try { mcpStarter.stop(); } catch (RuntimeException ignored) { } mcpStarter = null; }
        if (starter != null) { try { starter.stop(); } catch (RuntimeException ignored) { } starter = null; }
        moduleManager.stop(this::moduleContext);
        closeResources();
        state.set(RuntimeState.STOPPED);
    }

    @Override public List<ModuleMetadata> moduleDescriptors() { return moduleManager.descriptors(); }
    @Override public PermissionRegistry permissions() { return permissions; }
    @Override public <T> Optional<T> find(Class<T> type) { return Optional.ofNullable(getBean(type)); }

    @Override public int port() { return config.port(); }
    @Override public String env() { return config.env(); }
    @Override public int workers() { return config.workers(); }
    @Override public Consumer<?> routeConfig() { return null; }
    @Override public List<Object> services() { return List.copyOf(services); }
    @Override public List<RouteEntry> directRoutes() { return List.copyOf(directRoutes); }
    @Override public List<String> scanPackages() { return List.of(); }
    @Override public List<Class<?>> serviceClasses() { return List.of(); }
    @Override public String staticFilesPath() { return staticFilesPath; }
    @Override public boolean spa() { return spa; }
    @Override public String corsOrigin() { return corsOrigin; }
    @Override public CorsConfig corsConfig() { return corsConfig; }
    @Override public long maxBodySize() { return config.maxBodySize(); }
    @Override public long maxUploadSize() { return config.maxUploadSize(); }
    @Override public int shutdownTimeout() { return config.shutdownTimeout(); }
    @Override public boolean accessLog() { return accessLog; }
    @Override public String accessLogFormat() { return accessLogFormat; }
    @Override public int requestTimeout() { return config.requestTimeout(); }
    @Override public boolean gzip() { return gzip; }
    @Override public int gzipMinSize() { return gzipMinSize; }
    @Override public JsonConfig jsonConfig() { return jsonConfig; }
    @Override public int mcpPort() { return mcpPort; }
    @Override public PrintStream mcpStdout() { return mcpStdout; }
    @Override public McpRouterSpec mcpRouter() { return mcpRouter != null ? mcpRouter : getBean(McpRouterSpec.class); }
    @Override public Set<String> allowedHosts() { return config.allowedHosts(); }
    @Override public boolean securityHeaders() { return config.securityHeaders(); }
    @Override public boolean openApiEnabled() { return config.openApiEnabled(); }
    @Override public int concurrencyLimit() { return config.concurrencyLimit(); }
    @Override public int rateLimitPerSecond() { return config.rateLimitPerSecond(); }
    @Override public String publicOrigin() { return config.publicOrigin(); }
    @Override public boolean isShuttingDown() { return shuttingDown.get(); }
    @Override public void emit(AuraEvent event) { for (AuraEventListener listener : eventListeners) try { listener.onEvent(event); } catch (RuntimeException ignored) { } }
    @Override public String prop(String key) {
        String envKey = key.replace('.', '_').replace('-', '_').toUpperCase();
        String value = System.getenv(envKey);
        if (value != null) return value;
        value = System.getProperty(key);
        return value != null ? value : properties.get(key);
    }
    @Override public <T> T getBean(Class<T> type) {
        for (Map.Entry<Class<?>, Object> entry : registry.entrySet()) if (type.isAssignableFrom(entry.getKey())) return type.cast(entry.getValue());
        return null;
    }
    @Override public Function<BaseContext, String> authFunction() { return authFunction; }
    @Override public BiPredicate<String, String> permissionChecker() { return permissionChecker; }
    @Override public Map<Class<? extends Exception>, BaseExceptionHandler<?>> exceptionHandlers() { return exceptionHandlers; }
    @Override public List<BaseHandler> beforeHandlers() { return List.copyOf(beforeHandlers); }
    @Override public List<BaseHandler> afterHandlers() { return List.copyOf(afterHandlers); }
    @Override public Function<Object, Object> resultWrapper() { return resultWrapper; }
    @Override public SnapshotOptions snapshotOptions() { return snapshotOptions; }

    private void failStart(Throwable failure) {
        if (keepAliveThread != null) keepAliveThread.interrupt();
        if (mcpStarter != null) { try { mcpStarter.stop(); } catch (RuntimeException ignored) { } mcpStarter = null; }
        if (starter != null) { try { starter.stop(); } catch (RuntimeException ignored) { } starter = null; }
        moduleManager.stop(this::moduleContext);
        closeResources();
        state.set(RuntimeState.STOPPED);
    }

    private void closeResources() {
        for (int i = closeables.size() - 1; i >= 0; i--) {
            try { closeables.get(i).close(); } catch (Exception ignored) { }
        }
        closeables.clear();
    }

    private <T> T register(T value) {
        if (value == null) throw new IllegalArgumentException("registered value must not be null");
        registry.put(value.getClass(), value);
        trackLifecycle(value);
        return value;
    }
    private void register(String name, Object value) { if (name == null || name.isBlank()) throw new IllegalArgumentException("registration name must not be blank"); namedRegistry.put(name, register(value)); }
    private ModuleContext moduleContext(AuraModule module) { return new ModuleContextImpl(module, module.descriptor()); }
    private void trackLifecycle(Object value) { if (value instanceof AutoCloseable closeable && !closeables.contains(closeable)) closeables.add(closeable); }

    private final class ModuleContextImpl implements ModuleContext {
        private final ModuleMetadata descriptor;
        private ModuleContextImpl(AuraModule module, ModuleMetadata descriptor) { this.descriptor = descriptor; }
        @Override public ModuleMetadata currentModule() { return descriptor; }
        @Override public <T> T require(Class<T> type) { T value = getBean(type); if (value == null) throw new ModuleException("AURA_CAPABILITY_MISSING", descriptor.id(), "Module " + descriptor.id() + " requires capability " + type.getName()); return value; }
        @Override public <T> Optional<T> find(Class<T> type) { return Optional.ofNullable(getBean(type)); }
        @Override public void register(Object value) { ModularRuntime.this.register(value); }
        @Override public void register(String name, Object value) { ModularRuntime.this.register(name, value); }
        @Override public void service(Object service) { ModularRuntime.this.register(service); services.add(service); }
        @Override public void route(RouteContribution route) {
            if (route == null) throw new ModuleException("AURA_ROUTE_CONTRIBUTION_INVALID", descriptor.id(), "Route contribution must not be null");
            if (route.handler() == null) throw new ModuleException("AURA_ROUTE_HANDLER_MISSING", descriptor.id(), "Route contribution " + route.method() + " " + route.path() + " has no handler");
            route.parameters();
            directRoutes.add(new RouteEntry(route.method(), route.path(), route.handler(), route.description(), route.metadata()));
        }
        @Override public void healthCheck(io.aura.contract.HealthCheck check) { if (check != null) healthChecks.add(check); }
        @Override public PermissionRegistry permissions() { return permissions; }
        @Override public String property(String key) { String prefix = "aura." + descriptor.id() + "."; if (key == null || !key.startsWith(prefix)) throw new ModuleException("AURA_MODULE_PROPERTY_SCOPE", descriptor.id(), "Module properties must use prefix " + prefix); return prop(key); }
        @Override public <T> T config(Class<T> recordType) { if (recordType == null || !recordType.isRecord()) throw new ModuleException("AURA_MODULE_CONFIG_TYPE", descriptor.id(), "Module config must be a record type"); return ConfigBinder.bind(properties(descriptor.id()), "aura." + descriptor.id() + ".", recordType); }
        private Map<String, String> properties(String id) { Map<String, String> values = new java.util.LinkedHashMap<>(); String prefix = "aura." + id + "."; properties.forEach((key, value) -> { if (key.startsWith(prefix)) values.put(key, prop(key)); }); return values; }
    }
}
