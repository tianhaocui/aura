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
 * Independent 1.0 runtime used by AuraApp. It owns modular contributions and
 * lifecycle state behind the stable runtime contracts.
 */
final class ModularRuntime implements AuraWebRuntime {
    private final AuraConfigSnapshot config;
    private final javax.net.ssl.SSLContext tlsContext;
    private final Map<String, String> properties = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> registry = new ConcurrentHashMap<>();
    private final Map<String, Object> namedRegistry = new ConcurrentHashMap<>();
    private final List<Object> services = new CopyOnWriteArrayList<>();
    private final List<RouteEntry> directRoutes = new CopyOnWriteArrayList<>();
    private final List<WebSocketContribution> webSocketContributions = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> closeables = new CopyOnWriteArrayList<>();
    private final List<io.aura.contract.HealthCheck> healthChecks = new CopyOnWriteArrayList<>();
    private final List<AuraEventListener> eventListeners = new CopyOnWriteArrayList<>();
    private final List<BaseHandler> beforeHandlers = new CopyOnWriteArrayList<>();
    private final List<BaseHandler> afterHandlers = new CopyOnWriteArrayList<>();
    private final Map<Class<? extends Exception>, BaseExceptionHandler<?>> exceptionHandlers = new ConcurrentHashMap<>();
    private final PermissionRegistry permissions = new PermissionRegistry();
    private final ModuleManager moduleManager = new ModuleManager();
    private final AtomicReference<RuntimeState> state = new AtomicReference<>(RuntimeState.NEW);
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private BiPredicate<String, String> permissionChecker;
    private Function<BaseContext, String> authFunction;
    private io.aura.contract.McpAuthenticator mcpAuthenticator;
    private io.aura.contract.McpAuthorizer mcpAuthorizer;
    private Function<Object, Object> resultWrapper;
    private String resultWrapperModule;
    private io.aura.web.AdmissionPolicy admissionPolicy;
    private String admissionPolicyModule;
    private int mcpPort = -1;
    private PrintStream mcpStdout;
    private McpRouterSpec mcpRouter;
    private AuraStarter starter;
    private McpStarter mcpStarter;
    private volatile Thread keepAliveThread;
    private volatile boolean devMode;

    ModularRuntime(AuraConfig source) {
        this.config = AuraConfigSnapshot.from(source);
        this.devMode = config.devMode();
        this.tlsContext = createTlsContext(source);
        this.properties.putAll(source.properties());
        registry.put(ModularRuntime.class, this);
    }

    @Override public javax.net.ssl.SSLContext tlsContext() { return tlsContext; }

    private static javax.net.ssl.SSLContext createTlsContext(AuraConfig source) {
        if (!source.tlsEnabled()) return null;
        char[] password = source.keyStorePassword().toCharArray();
        try {
            java.security.KeyStore keyStore = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
            try (java.io.InputStream input = java.nio.file.Files.newInputStream(java.nio.file.Path.of(source.keyStorePath()))) {
                keyStore.load(input, password);
            }
            boolean hasPrivateKey = false;
            java.util.Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                java.security.cert.Certificate[] chain = keyStore.getCertificateChain(alias);
                if (keyStore.entryInstanceOf(alias, java.security.KeyStore.PrivateKeyEntry.class)
                        && chain != null && chain.length > 0) {
                    hasPrivateKey = true;
                    break;
                }
            }
            if (!hasPrivateKey) {
                throw new IllegalArgumentException(
                        "TLS keystore must contain a PrivateKeyEntry with a certificate chain");
            }
            var keyManagerFactory = javax.net.ssl.KeyManagerFactory.getInstance(
                    javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, password);
            var context = javax.net.ssl.SSLContext.getInstance("TLS");
            context.init(keyManagerFactory.getKeyManagers(), null, null);
            return context;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("TLS keystore could not be initialized", error);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    @Override public RuntimeState state() { return state.get(); }
    @Override public AuraConfigSnapshot config() { return config; }
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
    @Override public AuraRuntime mcpAuthenticate(io.aura.contract.McpAuthenticator authenticator) {
        mcpAuthenticator = authenticator;
        return this;
    }
    @Override public AuraRuntime mcpAuthorize(io.aura.contract.McpAuthorizer authorizer) {
        mcpAuthorizer = authorizer;
        return this;
    }
    @Override public AuraRuntime property(String key, String value) {
        if (key == null || !key.startsWith("aura.")) {
            throw new IllegalArgumentException("AuraApp properties must use the aura.<module>.* namespace");
        }
        if (moduleManager.installed() || state.get() != RuntimeState.NEW) {
            throw new IllegalStateException("AuraApp properties must be configured before module preparation");
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
            if (arg.startsWith("--config=")) {
                throw new IllegalArgumentException(
                        "AuraApp configuration is frozen at creation; use AuraApp.create(Path.of(\"...\")) or AuraApp.create(Properties)");
            }
            if ("--mcp-stdio".equals(arg)) stdio = true;
            if ("--dev".equals(arg)) devMode = true;
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
            moduleManager.install(this::moduleContext, true);
            StartupCheck.requireParameterNames(services);
            starter = java.util.ServiceLoader.load(AuraStarter.class).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No AuraStarter found. Add aura-web to your dependencies."));
            starter.start(this);
            state.set(RuntimeState.RUNNING);
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "aura-shutdown"));
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
    @Override public <T> Optional<T> find(String name, Class<T> type) {
        if (name == null || type == null) return Optional.empty();
        Object value = namedRegistry.get(name);
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }

    @Override public int port() { return config.port(); }
    @Override public String env() { return config.env(); }
    @Override public int workers() { return config.workers(); }
    @Override public Consumer<?> routeConfig() { return null; }
    @Override public List<Object> services() { return List.copyOf(services); }
    @Override public List<RouteEntry> directRoutes() { return List.copyOf(directRoutes); }
    @Override public List<WebSocketContribution> webSocketContributions() { return List.copyOf(webSocketContributions); }
    @Override public List<String> scanPackages() { return List.of(); }
    @Override public List<Class<?>> serviceClasses() { return List.of(); }
    @Override public String staticFilesPath() { return config.staticFilesPath(); }
    @Override public boolean spa() { return config.spa(); }
    @Override public String corsOrigin() { return config.corsOrigin(); }
    @Override public CorsConfig corsConfig() { return config.corsConfig(); }
    @Override public long maxBodySize() { return config.maxBodySize(); }
    @Override public long maxUploadSize() { return config.maxUploadSize(); }
    @Override public long maxWebSocketMessageSize() { return config.maxWebSocketMessageSize(); }
    @Override public int maxWebSocketConnections() { return config.maxWebSocketConnections(); }
    @Override public int webSocketIdleTimeout() { return config.webSocketIdleTimeout(); }
    @Override public int shutdownTimeout() { return config.shutdownTimeout(); }
    @Override public boolean accessLog() { return config.accessLog(); }
    @Override public String accessLogFormat() { return config.accessLogFormat(); }
    @Override public int requestTimeout() { return config.requestTimeout(); }
    @Override public boolean gzip() { return config.gzip(); }
    @Override public int gzipMinSize() { return config.gzipMinSize(); }
    @Override public JsonConfig jsonConfig() { return config.jsonConfig(); }
    @Override public int mcpPort() { return mcpPort; }
    @Override public PrintStream mcpStdout() { return mcpStdout; }
    @Override public McpRouterSpec mcpRouter() { return mcpRouter != null ? mcpRouter : getBean(McpRouterSpec.class); }
    @Override public Set<String> allowedHosts() { return config.allowedHosts(); }
    @Override public Set<String> trustedProxies() { return config.trustedProxies(); }
    @Override public boolean securityHeaders() { return config.securityHeaders(); }
    @Override public boolean openApiEnabled() { return config.openApiEnabled(); }
    @Override public boolean devMode() { return devMode; }
    @Override public int concurrencyLimit() { return config.concurrencyLimit(); }
    @Override public int rateLimitPerSecond() { return config.rateLimitPerSecond(); }
    @Override public int rateLimitMaxKeys() { return config.rateLimitMaxKeys(); }
    @Override public String publicOrigin() { return config.publicOrigin(); }
    @Override public boolean isShuttingDown() { return shuttingDown.get(); }
    @Override public List<io.aura.contract.HealthCheck> healthChecks() { return List.copyOf(healthChecks); }
    @Override public io.aura.contract.HealthStatus healthStatus() {
        if (isShuttingDown()) return io.aura.contract.HealthStatus.DOWN;
        return HealthProbe.check(healthChecks, config.healthCheckTimeout());
    }
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
    @Override public io.aura.contract.McpAuthenticator mcpAuthenticator() { return mcpAuthenticator; }
    @Override public io.aura.contract.McpAuthorizer mcpAuthorizer() { return mcpAuthorizer; }
    @Override public Map<Class<? extends Exception>, BaseExceptionHandler<?>> exceptionHandlers() { return exceptionHandlers; }
    @Override public List<BaseHandler> beforeHandlers() { return List.copyOf(beforeHandlers); }
    @Override public List<BaseHandler> afterHandlers() { return List.copyOf(afterHandlers); }
    @Override public Function<Object, Object> resultWrapper() { return resultWrapper; }
    @Override public io.aura.web.AdmissionPolicy admissionPolicy() { return admissionPolicy; }
    @Override public SnapshotOptions snapshotOptions() { return config.snapshotOptions(); }

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
    private void register(String name, Object value) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("registration name must not be blank");
        Object registered = register(value);
        Object existing = namedRegistry.putIfAbsent(name, registered);
        if (existing != null) throw new IllegalStateException("Named capability already registered: " + name);
    }
    private ModuleContext moduleContext(AuraModule module) { return new ModuleContextImpl(module, module.descriptor()); }
    private void trackLifecycle(Object value) { if (value instanceof AutoCloseable closeable && !closeables.contains(closeable)) closeables.add(closeable); }

    private final class ModuleContextImpl implements ModuleContext {
        private final ModuleMetadata descriptor;
        private ModuleContextImpl(AuraModule module, ModuleMetadata descriptor) { this.descriptor = descriptor; }
        @Override public ModuleMetadata currentModule() { return descriptor; }
        @Override public <T> T require(Class<T> type) { T value = getBean(type); if (value == null) throw new ModuleException("AURA_CAPABILITY_MISSING", descriptor.id(), "Module " + descriptor.id() + " requires capability " + type.getName()); return value; }
        @Override public <T> Optional<T> find(Class<T> type) { return Optional.ofNullable(getBean(type)); }
        @Override public <T> Optional<T> find(String name, Class<T> type) {
            return ModularRuntime.this.find(name, type);
        }
        @Override public void register(Object value) { ModularRuntime.this.register(value); }
        @Override public void register(String name, Object value) { ModularRuntime.this.register(name, value); }
        @Override public void service(Object service) { ModularRuntime.this.register(service); services.add(service); }
        @Override public void route(RouteContribution route) {
            if (route == null) throw new ModuleException("AURA_ROUTE_CONTRIBUTION_INVALID", descriptor.id(), "Route contribution must not be null");
            if (route.handler() == null) throw new ModuleException("AURA_ROUTE_HANDLER_MISSING", descriptor.id(), "Route contribution " + route.method() + " " + route.path() + " has no handler");
            route.parameters();
            directRoutes.add(new RouteEntry(route.method(), route.path(), route.handler(), route.description(), route.metadata()));
        }
        @Override public void webSocket(String path, java.util.function.Consumer<io.aura.web.WsHandler> configure) {
            webSocketContributions.add(new WebSocketContribution(path, configure));
        }
        @Override public void before(BaseHandler handler) {
            if (handler == null) throw new ModuleException("AURA_MIDDLEWARE_INVALID", descriptor.id(), "Before middleware must not be null");
            beforeHandlers.add(handler);
        }
        @Override public void after(BaseHandler handler) {
            if (handler == null) throw new ModuleException("AURA_MIDDLEWARE_INVALID", descriptor.id(), "After middleware must not be null");
            afterHandlers.add(handler);
        }
        @Override public <T extends Exception> void exception(Class<T> type, BaseExceptionHandler<T> handler) {
            if (type == null || handler == null) {
                throw new ModuleException("AURA_EXCEPTION_HANDLER_INVALID", descriptor.id(), "Exception type and handler must not be null");
            }
            exceptionHandlers.put(type, handler);
        }
        @Override public void resultWrapper(Function<Object, Object> wrapper) {
            if (wrapper == null) throw new ModuleException("AURA_RESULT_WRAPPER_INVALID", descriptor.id(), "Result wrapper must not be null");
            if (resultWrapper != null) {
                throw new ModuleException("AURA_RESULT_WRAPPER_DUPLICATE", descriptor.id(),
                        "Result wrapper already contributed by module " + resultWrapperModule);
            }
            resultWrapper = wrapper;
            resultWrapperModule = descriptor.id();
        }
        @Override public void admissionPolicy(io.aura.web.AdmissionPolicy policy) {
            if (policy == null) throw new ModuleException("AURA_ADMISSION_POLICY_INVALID", descriptor.id(), "Admission policy must not be null");
            if (admissionPolicy != null) {
                throw new ModuleException("AURA_ADMISSION_POLICY_DUPLICATE", descriptor.id(),
                        "Admission policy already contributed by module " + admissionPolicyModule);
            }
            admissionPolicy = policy;
            admissionPolicyModule = descriptor.id();
        }
        @Override public void healthCheck(io.aura.contract.HealthCheck check) { if (check != null) healthChecks.add(check); }
        @Override public PermissionRegistry permissions() { return permissions; }
        @Override public String property(String key) { String prefix = "aura." + descriptor.id() + "."; if (key == null || !key.startsWith(prefix)) throw new ModuleException("AURA_MODULE_PROPERTY_SCOPE", descriptor.id(), "Module properties must use prefix " + prefix); return prop(key); }
        @Override public <T> T config(Class<T> recordType) {
            if (recordType == null || !recordType.isRecord()) {
                throw new ModuleException("AURA_MODULE_CONFIG_TYPE", descriptor.id(),
                        "Module config must be a record type");
            }
            String prefix = "aura." + descriptor.id() + ".";
            Map<String, String> values = new java.util.LinkedHashMap<>();
            for (java.lang.reflect.RecordComponent component : recordType.getRecordComponents()) {
                String key = prefix + component.getName();
                String value = prop(key);
                if (value != null) values.put(key, value);
            }
            return ConfigBinder.bind(values, prefix, recordType);
        }
    }
}
