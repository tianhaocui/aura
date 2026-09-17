package io.aura;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import io.aura.snapshot.SnapshotOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Legacy pre-1.0 builder. New applications must use {@link AuraApp}; this
 * compatibility surface is retained temporarily and will be removed in 1.0.x.
 */
@Deprecated(forRemoval = true, since = "1.0.0")
public class Aura implements AuraWebRuntime {

    public enum Lifecycle { NEW, STARTING, RUNNING, STOPPING, STOPPED }

    private static final Logger log = LoggerFactory.getLogger(Aura.class);

    private int port = 8080;
    private String env = "dev";
    private int workers = 200;
    private final Map<String, String> props = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> registry = new ConcurrentHashMap<>();
    private final Map<String, Object> namedRegistry = new ConcurrentHashMap<>();
    private final List<Consumer<Aura>> startHooks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<Consumer<Aura>> stopHooks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private Consumer<?> routeConfig;
    private final List<Object> services = new ArrayList<>();
    private final List<RouteEntry> directRoutes = new ArrayList<>();
    private final List<String> scanPackages = new ArrayList<>();
    private final List<Class<?>> serviceClasses = new ArrayList<>();
    private final List<Reloadable> reloadables = new ArrayList<>();
    private final List<AutoCloseable> closeables = new ArrayList<>();
    private final List<AuraPlugin> plugins = new ArrayList<>();
    private final ModuleManager moduleManager = new ModuleManager();
    private String staticFilesPath;
    private boolean spaMode;
    private String corsOrigin;
    private CorsConfig corsConfig;
    private long maxBodySize = 10 * 1024 * 1024; // 10MB default
    private long maxUploadSize = 1024 * 1024; // 1MB default for materialized UploadedFile values
    private int shutdownTimeout = 30;
    private String accessLogFormat;
    private int requestTimeout;
    private boolean gzip;
    private int gzipMinSize = 1024;
    private JsonConfig jsonConfig = new JsonConfig();
    private int mcpPort = -1;
    private java.io.PrintStream mcpStdout;
    private io.aura.contract.McpRouterSpec mcpRouter;
    private java.util.function.Function<io.aura.web.BaseContext, String> authFunction;
    private java.util.function.BiPredicate<String, String> permissionChecker;
    private JwtSupport jwtSupport;
    private String jwtSecret;
    private long jwtExpireSeconds;
    private final java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile boolean shuttingDown;
    private volatile Thread keepAliveThread;
    private final java.util.LinkedHashMap<Class<? extends Exception>, io.aura.web.BaseExceptionHandler<?>> exceptionHandlers = new java.util.LinkedHashMap<>();
    private final List<io.aura.web.BaseHandler> beforeHandlers = new ArrayList<>();
    private final List<io.aura.web.BaseHandler> afterHandlers = new ArrayList<>();
    private java.util.function.Function<Object, Object> resultWrapper;
    private final SnapshotOptions snapshotOptions = new SnapshotOptions();
    private final AuraConfig config;
    private final AtomicReference<Lifecycle> lifecycle = new AtomicReference<>(Lifecycle.NEW);
    private final List<io.aura.contract.HealthCheck> healthChecks = new CopyOnWriteArrayList<>();
    private final io.aura.contract.PermissionRegistry permissionRegistry = new io.aura.contract.PermissionRegistry();
    private final List<AuraEventListener> eventListeners = new CopyOnWriteArrayList<>();

    private AuraStarter starter;
    private McpStarter mcpStarter;
    private boolean reloadMode;

    private Aura() { this(new AuraConfig(), true); }

    private Aura(AuraConfig config, boolean loadDefaults) {
        this.config = config.validate();
        this.port = config.port(); this.env = config.env(); this.workers = config.workers();
        this.maxBodySize = config.maxBodySize(); this.maxUploadSize = config.maxUploadSize();
        this.shutdownTimeout = config.shutdownTimeout(); this.requestTimeout = config.requestTimeout();
        if (loadDefaults) {
            loadConfig("aura.properties");
            applyFrameworkProps();
            loadConfig("aura-" + env + ".properties");
        }
    }

    public static Aura create() {
        if (ReloadState.RELOAD_INSTANCE != null) {
            Aura app = ReloadState.RELOAD_INSTANCE;
            app.reloadMode = true;
            app.directRoutes.clear();
            app.services.clear();
            app.routeConfig = null;
            app.scanPackages.clear();
            app.beforeHandlers.clear();
            app.afterHandlers.clear();
            return app;
        }
        return new Aura();
    }

    public static Aura create(AuraConfig config) { return new Aura(config == null ? new AuraConfig() : config, false); }
    /** Returns a detached immutable view; mutate the legacy builder through its fluent methods. */
    @Override public AuraConfigSnapshot config() { return config.snapshotView(); }
    public Lifecycle lifecycle() { return lifecycle.get(); }
    @Override public io.aura.contract.RuntimeState state() {
        return io.aura.contract.RuntimeState.valueOf(lifecycle.get().name());
    }
    public Aura configure(Consumer<AuraConfig> consumer) { consumer.accept(config); config.validate(); syncConfigFields(); return this; }

    /** Explicitly install a capability or business module. Modules are ordered by declared dependencies. */
    public Aura use(AuraModule module) {
        moduleManager.add(module);
        return this;
    }

    public List<AuraModule> modules() { return moduleManager.modules(); }
    public List<io.aura.contract.ModuleMetadata> moduleDescriptors() { return moduleManager.descriptors(); }
    @Override public <T> java.util.Optional<T> find(Class<T> type) {
        return java.util.Optional.ofNullable(getBean(type));
    }
    public Aura observe(AuraEventListener listener) {
        if (listener == null) throw new IllegalArgumentException("event listener must not be null");
        eventListeners.add(listener);
        return this;
    }
    public List<AuraEventListener> eventListeners() { return List.copyOf(eventListeners); }
    public void emit(AuraEvent event) {
        if (event == null) return;
        for (AuraEventListener listener : eventListeners) {
            try { listener.onEvent(event); }
            catch (RuntimeException ignored) { log.warn("Aura event listener failed for {}", event.type()); }
        }
    }
    private void syncConfigFields() {
        port = config.port(); env = config.env(); workers = config.workers(); maxBodySize = config.maxBodySize();
        maxUploadSize = config.maxUploadSize(); shutdownTimeout = config.shutdownTimeout(); requestTimeout = config.requestTimeout();
    }

    public static void setReloadInstance(Aura app) { ReloadState.RELOAD_INSTANCE = app; }
    public static void clearReloadInstance() { ReloadState.RELOAD_INSTANCE = null; }
    public static boolean isDevMode() { return ReloadState.devMode; }

    public Aura dev(boolean enable) {
        ReloadState.devMode = enable;
        return this;
    }

    public Aura onReload(Runnable hook) {
        ReloadState.cleanupHooks.add(hook);
        return this;
    }

    public void fireReloadCleanup() {
        ReloadState.fireCleanup();
    }

    public static Aura run(String... args) {
        String callerClass = new Throwable().getStackTrace()[1].getClassName();
        String pkg = callerClass.contains(".") ? callerClass.substring(0, callerClass.lastIndexOf('.')) : "";
        Aura app = new Aura();
        if (!pkg.isEmpty()) app.scan(pkg);
        app.start(args);
        return app;
    }


    public Aura port(int port) {
        config.port(port).validate(); this.port = port;
        return this;
    }

    public Aura env(String env) {
        config.env(env); this.env = env;
        validateJwtConfiguration();
        return this;
    }

    public Aura workers(int workers) {
        config.workers(workers).validate(); this.workers = workers;
        return this;
    }

    public Aura rateLimit(int requestsPerSecond) { config.rateLimitPerSecond(requestsPerSecond).validate(); return this; }
    public Aura concurrencyLimit(int maxConcurrent) { config.concurrencyLimit(maxConcurrent).validate(); return this; }
    public Aura publicOrigin(String origin) { config.publicOrigin(origin); return this; }
    public Aura allowedHosts(String... hosts) { config.allowedHosts(hosts); return this; }
    public Aura securityHeaders(boolean enabled) { config.securityHeaders(enabled); return this; }
    public Aura openApi(boolean enabled) { config.openApi(enabled); return this; }
    public Aura tls(String keyStorePath, String password) { config.tls(keyStorePath, password).validate(); return this; }
    public int rateLimitPerSecond() { return config.rateLimitPerSecond(); }
    public int concurrencyLimit() { return config.concurrencyLimit(); }
    public String publicOrigin() { return config.publicOrigin(); }
    @Override public javax.net.ssl.SSLContext tlsContext() {
        if (!config.tlsEnabled()) return null;
        try {
            java.security.KeyStore keyStore = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
            try (java.io.InputStream input = java.nio.file.Files.newInputStream(java.nio.file.Path.of(config.keyStorePath()))) {
                keyStore.load(input, config.keyStorePassword().toCharArray());
            }
            var keyManagerFactory = javax.net.ssl.KeyManagerFactory.getInstance(
                    javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            char[] password = config.keyStorePassword().toCharArray();
            try {
                keyManagerFactory.init(keyStore, password);
            } finally {
                java.util.Arrays.fill(password, '\0');
            }
            var context = javax.net.ssl.SSLContext.getInstance("TLS");
            context.init(keyManagerFactory.getKeyManagers(), null, null);
            return context;
        } catch (Exception error) {
            throw new IllegalArgumentException("TLS keystore could not be initialized", error);
        }
    }
    public java.util.Set<String> allowedHosts() { return config.allowedHosts(); }
    public boolean securityHeaders() { return config.securityHeaders(); }
    public boolean openApiEnabled() { return config.openApiEnabled(); }

    public Aura set(String key, String value) {
        props.put(key, value);
        return this;
    }

    @Override public Aura property(String key, String value) { return set(key, value); }

    public Aura staticFiles(String path) {
        this.staticFilesPath = path;
        return this;
    }

    public Aura spa(boolean enabled) {
        this.spaMode = enabled;
        return this;
    }

    public Aura cors(boolean enabled) {
        this.corsOrigin = enabled ? "*" : null;
        return this;
    }

    public Aura cors(String origin) {
        this.corsOrigin = origin;
        return this;
    }

    public Aura cors(java.util.function.Consumer<CorsConfig> config) {
        CorsConfig c = new CorsConfig();
        config.accept(c);
        if (c.credentials() && c.hasWildcardOrigin()) {
            throw new IllegalArgumentException(
                    "Credentialed CORS requires explicit origins; wildcard '*' is not allowed");
        }
        this.corsConfig = c;
        this.corsOrigin = "__cors_config__";
        return this;
    }

    public Aura maxBodySize(long bytes) {
        config.maxBodySize(bytes).validate(); this.maxBodySize = bytes;
        return this;
    }

    public Aura maxUploadSize(long bytes) {
        // Preserve the legacy fluent API's disabled-upload sentinel; AuraConfig itself is strict.
        if (bytes < 0) { this.maxUploadSize = 0; return this; }
        if (bytes == 0) { this.maxUploadSize = 0; return this; }
        config.maxUploadSize(bytes).validate(); this.maxUploadSize = bytes;
        return this;
    }

    public Aura shutdownTimeout(int seconds) {
        config.shutdownTimeout(seconds).validate(); this.shutdownTimeout = seconds;
        return this;
    }

    public Aura health() {
        get("/health", (io.aura.web.BaseHandler) ctx -> {
            if (shuttingDown) {
                ctx.status(503).json(java.util.Map.of("status", "DOWN"));
            } else {
                ctx.json(java.util.Map.of("status", "UP"));
            }
        });
        get("/health/live", (io.aura.web.BaseHandler) ctx -> ctx.json(java.util.Map.of("status", "UP")));
        get("/health/ready", (io.aura.web.BaseHandler) ctx -> {
            boolean ready = lifecycle.get() == Lifecycle.RUNNING
                    && healthStatus() == io.aura.contract.HealthStatus.UP;
            ctx.status(ready ? 200 : 503).json(java.util.Map.of("status", ready ? "UP" : "DOWN"));
        });
        return this;
    }

    public Aura healthCheck(io.aura.contract.HealthCheck check) { if (check != null) healthChecks.add(check); return this; }
    @Override public List<io.aura.contract.HealthCheck> healthChecks() { return List.copyOf(healthChecks); }
    @Override public io.aura.contract.HealthStatus healthStatus() {
        if (shuttingDown) return io.aura.contract.HealthStatus.DOWN;
        return HealthProbe.check(healthChecks, config.healthCheckTimeout());
    }
    public io.aura.contract.PermissionRegistry permissions() { return permissionRegistry; }

    public boolean isShuttingDown() { return shuttingDown; }

    public Aura accessLog(boolean enabled) {
        this.accessLogFormat = enabled ? "text" : null;
        return this;
    }

    public Aura accessLog(String format) {
        this.accessLogFormat = format;
        return this;
    }

    public Aura requestTimeout(int seconds) {
        seconds = Math.max(0, seconds);
        config.requestTimeout(seconds).validate(); this.requestTimeout = seconds;
        return this;
    }

    public Aura gzip(boolean enabled) {
        this.gzip = enabled;
        return this;
    }

    public Aura gzipMinSize(int bytes) {
        this.gzipMinSize = Math.max(0, bytes);
        return this;
    }

    public Aura auth(java.util.function.Function<io.aura.web.BaseContext, String> authFunction) {
        this.authFunction = authFunction;
        return this;
    }

    @Override public AuraRuntime authenticate(java.util.function.Function<io.aura.web.BaseContext, String> authenticator) {
        return auth(authenticator);
    }

    public Aura authorize(java.util.function.BiPredicate<String, String> permissionChecker) {
        this.permissionChecker = permissionChecker;
        return this;
    }

    @Override public java.util.function.BiPredicate<String, String> permissionChecker() { return permissionChecker; }

    public Aura jwt(String secret) {
        return jwt(secret, 604800);
    }

    public Aura jwt(String secret, long expireSeconds) {
        validateJwtConfiguration(secret, expireSeconds);
        this.jwtSecret = secret;
        this.jwtExpireSeconds = expireSeconds;
        this.jwtSupport = new JwtSupport(secret, expireSeconds);
        this.authFunction = ctx -> {
            String header = ctx.header("Authorization");
            if (header == null || !header.startsWith("Bearer ")) return null;
            return jwtSupport.verify(header.substring(7));
        };
        return this;
    }

    public String signJwt(long userId) {
        if (jwtSupport == null) throw new IllegalStateException("JWT not configured. Call app.jwt(secret) first.");
        return jwtSupport.sign(userId);
    }

    public String signJwt(String subject) {
        if (jwtSupport == null) throw new IllegalStateException("JWT not configured. Call app.jwt(secret) first.");
        return jwtSupport.sign(subject);
    }

    public String verifyJwt(String token) {
        if (jwtSupport == null) throw new IllegalStateException("JWT not configured. Call app.jwt(secret) first.");
        return jwtSupport.verify(token);
    }

    public <T extends Exception> Aura exception(Class<T> type, io.aura.web.BaseExceptionHandler<T> handler) {
        exceptionHandlers.put(type, handler);
        return this;
    }

    public java.util.Map<Class<? extends Exception>, io.aura.web.BaseExceptionHandler<?>> exceptionHandlers() {
        return exceptionHandlers;
    }

    public io.aura.web.BeforeBuilder before(io.aura.web.BaseHandler handler) {
        return new io.aura.web.BeforeBuilder(this, handler);
    }

    public Aura after(io.aura.web.BaseHandler handler) {
        afterHandlers.add(handler);
        return this;
    }

    public List<io.aura.web.BaseHandler> beforeHandlers() {
        return beforeHandlers;
    }

    public List<io.aura.web.BaseHandler> afterHandlers() {
        return afterHandlers;
    }

    public static io.aura.web.BaseHandler requireAuth() {
        return ctx -> {
            AuraWebRuntime app = (AuraWebRuntime) ctx.get("_app", Object.class);
            if (app == null || app.authFunction() == null) {
                throw new IllegalStateException("Auth not configured. Call app.auth() or app.jwt() first.");
            }
            String userId = app.authFunction().apply(ctx);
            if (userId == null) {
                ctx.status(401);
                ctx.json(java.util.Map.of("error", "Unauthorized"));
                ctx.abort();
            } else {
                ctx.set("_userId", userId);
            }
        };
    }

    public static io.aura.web.BaseHandler requirePermission(String permission) {
        if (permission == null || permission.isBlank()) {
            throw new IllegalArgumentException("permission must not be blank");
        }
        return ctx -> {
            AuraWebRuntime app = (AuraWebRuntime) ctx.get("_app", Object.class);
            String userId = ctx.get("_userId", String.class);
            if (app == null || app.permissionChecker() == null) {
                throw new IllegalStateException("Authorization not configured. Call app.authorize() first.");
            }
            if (userId == null || !app.permissionChecker().test(userId, permission)) {
                ctx.status(403).json(java.util.Map.of("error", "Forbidden", "permission", permission));
                ctx.abort();
            }
        };
    }

    public java.util.function.Function<io.aura.web.BaseContext, String> authFunction() { return authFunction; }

    public Aura jsonConfig(java.util.function.Consumer<JsonConfig> config) {
        config.accept(this.jsonConfig);
        return this;
    }

    public Aura resultWrapper(java.util.function.Function<Object, Object> wrapper) {
        this.resultWrapper = wrapper;
        return this;
    }

    public java.util.function.Function<Object, Object> resultWrapper() {
        return resultWrapper;
    }

    public Aura snapshot(Consumer<SnapshotOptions> config) {
        config.accept(snapshotOptions);
        return this;
    }

    public SnapshotOptions snapshotOptions() {
        return snapshotOptions;
    }

    public Aura mcp(boolean enabled) {
        this.mcpPort = enabled ? 0 : -1;
        return this;
    }

    public Aura mcp(int port) {
        this.mcpPort = port;
        return this;
    }

    public Aura mcp(io.aura.contract.McpRouterSpec mcpRouter) {
        this.mcpRouter = mcpRouter;
        return this;
    }

    public Aura routes(Consumer<io.aura.web.BaseRouter> routeConfig) {
        this.routeConfig = routeConfig;
        return this;
    }

    public Aura service(Object... services) {
        for (Object s : services) {
            this.services.add(s);
        }
        return this;
    }

    public Aura services(Class<?>... classes) {
        serviceClasses.addAll(List.of(classes));
        return this;
    }

    /** @deprecated Use {@code services(Class...)} instead. */
    @Deprecated
    public Aura scan(String... packages) {
        for (String pkg : packages) {
            this.scanPackages.add(pkg);
        }
        return this;
    }

    public Aura plugin(AuraPlugin plugin) {
        this.plugins.add(plugin);
        return this;
    }

    // --- direct routes (simplified API) ---

    public Aura get(String path, Object handler) {
        directRoutes.add(new RouteEntry("GET", path, handler, null));
        return this;
    }

    public Aura post(String path, Object handler) {
        directRoutes.add(new RouteEntry("POST", path, handler, null));
        return this;
    }

    public Aura put(String path, Object handler) {
        directRoutes.add(new RouteEntry("PUT", path, handler, null));
        return this;
    }

    public Aura delete(String path, Object handler) {
        directRoutes.add(new RouteEntry("DELETE", path, handler, null));
        return this;
    }

    public Aura patch(String path, Object handler) {
        directRoutes.add(new RouteEntry("PATCH", path, handler, null));
        return this;
    }

    public Aura head(String path, Object handler) {
        directRoutes.add(new RouteEntry("HEAD", path, handler, null));
        return this;
    }

    public Aura options(String path, Object handler) {
        directRoutes.add(new RouteEntry("OPTIONS", path, handler, null));
        return this;
    }

    public Aura onStart(Consumer<Aura> hook) {
        startHooks.add(hook);
        return this;
    }

    public Aura onStop(Consumer<Aura> hook) {
        stopHooks.add(hook);
        return this;
    }


    public String prop(String key) {
        String envKey = key.replace('.', '_').replace('-', '_').toUpperCase();
        String value = resolve(key, envKey);
        if (value == null && isDevMode()) {
            log.warn("[Aura] prop(\"{}\") returned null. Checked: env {} (not set), sysprop {} (not set), props map (not found). Hint: app.set(\"{}\", value) or set env {}.",
                    key, envKey, key, key, envKey);
        }
        return value;
    }

    public int prop(String key, int defaultValue) {
        String val = prop(key);
        if (val == null) return defaultValue;
        return Integer.parseInt(val);
    }

    public Map<String, String> props(String prefix) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : props.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                String resolved = prop(entry.getKey());
                result.put(entry.getKey(), resolved != null ? resolved : entry.getValue());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public <T> T props(String prefix, Class<T> recordType) {
        return ConfigBinder.bind(props(prefix), prefix, recordType);
    }




    public <T> Aura register(T instance) {
        registry.put(instance.getClass(), instance);
        return this;
    }

    public <T> Aura register(String name, T instance) {
        namedRegistry.put(name, instance);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> T getBean(Class<T> type) {
        for (Map.Entry<Class<?>, Object> entry : registry.entrySet()) {
            if (type.isAssignableFrom(entry.getKey())) {
                return (T) entry.getValue();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public <T> T getBean(String name, Class<T> type) {
        Object obj = namedRegistry.get(name);
        if (obj != null && type.isAssignableFrom(obj.getClass())) {
            return (T) obj;
        }
        return null;
    }


    @Override public void start(String[] args) {
        boolean mcpStdio = false;
        for (String arg : args) {
            if (arg.startsWith("--config=")) {
                loadExternalConfig(arg.substring(9));
            } else if (arg.startsWith("--scan=")) {
                scan(arg.substring(7).split(","));
            } else if ("--mcp-stdio".equals(arg)) {
                mcpStdio = true;
            } else if ("--dev".equals(arg)) {
                ReloadState.devMode = true;
            } else if (arg.startsWith("--") && arg.contains("=")) {
                int eq = arg.indexOf('=');
                String key = arg.substring(2, eq);
                String val = arg.substring(eq + 1);
                props.put(key, val);
            }
        }
        if ("dev".equalsIgnoreCase(System.getenv("AURA_ENV"))) {
            ReloadState.devMode = true;
        }
        applyFrameworkProps();
        if (mcpStdio) {
            this.mcpPort = -2;
            this.mcpStdout = System.out;
            System.setOut(System.err);
        }
        start();
    }

    @Override public void start() {
        if (reloadMode && lifecycle.get() == Lifecycle.RUNNING) {
            reloadMode = false;
            selfCheck();
            starter.reloadRoutes(this);
            return;
        }
        Lifecycle current = lifecycle.get();
        if (current == Lifecycle.RUNNING || current == Lifecycle.STARTING) return;
        if (current == Lifecycle.STOPPING || current == Lifecycle.STOPPED) throw new IllegalStateException("Aura cannot be started after stopping");
        if (!lifecycle.compareAndSet(Lifecycle.NEW, Lifecycle.STARTING)) return;
        try {
        config.validate();
        validateJwtConfiguration();
        if (reloadMode) {
            reloadMode = false;
        }
        ServiceLoader.load(AuraPlugin.class).forEach(p -> {
            if (!plugins.contains(p)) plugins.add(p);
        });
        for (AuraPlugin plugin : plugins) {
            plugin.install(this);
        }
        registry.putIfAbsent(Aura.class, this);
        installModules(true);
        if (!serviceClasses.isEmpty()) {
            List<Object> resolved = ServiceResolver.resolve(serviceClasses, registry);
            for (Object bean : resolved) {
                if (bean instanceof Reloadable r) reloadables.add(r);
                if (bean instanceof AutoCloseable c) closeables.add(c);
                if (bean.getClass().isAnnotationPresent(io.aura.annotation.Path.class)) {
                    services.add(bean);
                }
            }
        }
        ServiceLoader<AuraStarter> loader = ServiceLoader.load(AuraStarter.class);
        starter = loader.findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No AuraStarter found. Add aura-web to your dependencies."));
        selfCheck();
        try { starter.start(this); } catch (RuntimeException e) {
            stopStartedModules();
            lifecycle.set(Lifecycle.STOPPED);
            throw e;
        }
        lifecycle.set(Lifecycle.RUNNING);
        fireStart();

        if (mcpPort >= 0) {
            ServiceLoader<McpStarter> mcpLoader = ServiceLoader.load(McpStarter.class);
            mcpStarter = mcpLoader.findFirst().orElse(null);
            if (mcpStarter != null) {
                mcpStarter.start(this);
            } else {
                log.warn("MCP enabled but no McpStarter found. Add aura-mcp to your dependencies.");
            }
        }

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));

        if (mcpPort == -2) {
            ServiceLoader<McpStarter> mcpLoader = ServiceLoader.load(McpStarter.class);
            mcpStarter = mcpLoader.findFirst().orElse(null);
            if (mcpStarter != null) {
                mcpStarter.startStdio(this);
            } else {
                log.error("--mcp-stdio requires aura-mcp dependency.");
            }
        }

        // Keep JVM alive — Undertow threads may be daemon
        keepAliveThread = new Thread(() -> {
            try { new java.util.concurrent.CountDownLatch(1).await(); }
            catch (InterruptedException ignored) {}
        }, "aura-main");
        keepAliveThread.setDaemon(false);
        keepAliveThread.start();
        } catch (RuntimeException | Error failure) {
            failStart(failure);
            throw failure;
        }
    }

    private void failStart(Throwable failure) {
        shuttingDown = true;
        if (keepAliveThread != null) keepAliveThread.interrupt();
        if (mcpStarter != null) {
            try { mcpStarter.stop(); } catch (RuntimeException ignored) { }
            mcpStarter = null;
        }
        if (starter != null) {
            try { starter.stop(); } catch (RuntimeException ignored) { }
            starter = null;
        }
        stopStartedModules();
        lifecycle.set(Lifecycle.STOPPED);
        log.error("Aura startup failed", failure);
    }

    @Override public void stop() {
        Lifecycle current = lifecycle.get();
        if (current == Lifecycle.STOPPED || current == Lifecycle.STOPPING) return;
        if (!lifecycle.compareAndSet(current, Lifecycle.STOPPING)) return;
        stopped.set(true);
        shuttingDown = true;
        if (keepAliveThread != null) keepAliveThread.interrupt();
        if (mcpStarter != null) {
            mcpStarter.stop();
            mcpStarter = null;
        }
        if (starter != null) {
            starter.stop();
            starter = null;
        }
        stopStartedModules();
        for (int i = closeables.size() - 1; i >= 0; i--) {
            try { closeables.get(i).close(); }
            catch (Exception e) { log.error("Error closing {}", closeables.get(i).getClass().getSimpleName(), e); }
        }
        fireStop();
        lifecycle.set(Lifecycle.STOPPED);
    }

    public void reloadConfig() {
        for (Reloadable r : reloadables) {
            try { r.reload(this); }
            catch (Exception e) { log.error("[Aura] Reload failed: {}", r.getClass().getSimpleName(), e); }
        }
    }


    public int port() { return config.port(); }
    public String env() { return config.env(); }
    public int workers() { return config.workers(); }
    public Consumer<?> routeConfig() {
        return routeConfig;
    }
    public List<Object> services() { return services; }
    public List<RouteEntry> directRoutes() { return directRoutes; }
    public List<String> scanPackages() { return scanPackages; }
    public List<Class<?>> serviceClasses() { return serviceClasses; }
    public String staticFilesPath() { return staticFilesPath; }
    public boolean spa() { return spaMode; }
    public String corsOrigin() { return corsOrigin; }
    public CorsConfig corsConfig() { return corsConfig; }
    public long maxBodySize() { return config.maxBodySize(); }
    public long maxUploadSize() { return maxUploadSize; }
    public int shutdownTimeout() { return config.shutdownTimeout(); }
    public boolean accessLog() { return accessLogFormat != null; }
    public String accessLogFormat() { return accessLogFormat; }
    public int requestTimeout() { return config.requestTimeout(); }
    public boolean gzip() { return gzip; }
    public int gzipMinSize() { return gzipMinSize; }
    public JsonConfig jsonConfig() { return jsonConfig; }
    public int mcpPort() { return mcpPort; }
    public java.io.PrintStream mcpStdout() { return mcpStdout; }
    public io.aura.contract.McpRouterSpec mcpRouter() {
        if (mcpRouter != null) return mcpRouter;
        return getBean(io.aura.contract.McpRouterSpec.class);
    }

    /** Installs module contributions without starting external resources; used by TestClient. */
    @Override public void prepareModules() {
        if (moduleManager.installed()) return;
        installModules(false);
    }

    private void installModules(boolean startModules) { moduleManager.install(this::moduleContext, startModules); }

    private void startInstalledModules() { moduleManager.start(this::moduleContext); }

    private void stopStartedModules() { moduleManager.stop(this::moduleContext); }

    private ModuleContext moduleContext(AuraModule module) {
        return new ModuleContextImpl(module, module.descriptor());
    }

    private final class ModuleContextImpl implements ModuleContext {
        private final AuraModule module;
        private final io.aura.contract.ModuleMetadata descriptor;

        private ModuleContextImpl(AuraModule module, io.aura.contract.ModuleMetadata descriptor) {
            this.module = module;
            this.descriptor = descriptor;
        }

        @Override public io.aura.contract.ModuleMetadata currentModule() { return descriptor; }
        @Override public <T> T require(Class<T> type) {
            T value = Aura.this.getBean(type);
            if (value == null) throw new ModuleException("AURA_CAPABILITY_MISSING", descriptor.id(),
                    "Module " + descriptor.id() + " requires capability " + type.getName());
            return value;
        }
        @Override public <T> java.util.Optional<T> find(Class<T> type) { return java.util.Optional.ofNullable(Aura.this.getBean(type)); }
        @Override public void register(Object value) { Aura.this.register(value); trackLifecycle(value); }
        @Override public void register(String name, Object value) { Aura.this.register(name, value); trackLifecycle(value); }
        @Override public void service(Object service) { Aura.this.service(service); trackLifecycle(service); }
        @Override public void route(io.aura.contract.RouteContribution route) {
            if (route == null) {
                throw new ModuleException("AURA_ROUTE_CONTRIBUTION_INVALID", descriptor.id(),
                        "Route contribution must not be null");
            }
            if (route.handler() == null) {
                throw new ModuleException("AURA_ROUTE_HANDLER_MISSING", descriptor.id(),
                        "Route contribution " + route.method() + " " + route.path() + " has no handler");
            }
            try {
                route.parameters();
            } catch (IllegalArgumentException error) {
                throw new ModuleException("AURA_ROUTE_CONTRIBUTION_INVALID", descriptor.id(), error.getMessage());
            }
            directRoutes.add(new RouteEntry(route.method(), route.path(), route.handler(),
                    route.description(), route.metadata()));
        }
        @Override public void healthCheck(io.aura.contract.HealthCheck healthCheck) { Aura.this.healthCheck(healthCheck); }
        @Override public io.aura.contract.PermissionRegistry permissions() { return permissionRegistry; }
        @Override public String property(String key) {
            String prefix = "aura." + descriptor.id() + ".";
            if (key == null || !key.startsWith(prefix)) {
                throw new ModuleException("AURA_MODULE_PROPERTY_SCOPE", descriptor.id(),
                        "Module properties must use prefix " + prefix);
            }
            return Aura.this.prop(key);
        }
        @Override public <T> T config(Class<T> recordType) {
            if (recordType == null || !recordType.isRecord()) {
                throw new ModuleException("AURA_MODULE_CONFIG_TYPE", descriptor.id(),
                        "Module config must be a record type");
            }
            return Aura.this.props("aura." + descriptor.id() + ".", recordType);
        }

        private void trackLifecycle(Object value) {
            if (value instanceof Reloadable reloadable && !reloadables.contains(reloadable)) reloadables.add(reloadable);
            if (value instanceof AutoCloseable closeable && !closeables.contains(closeable)) closeables.add(closeable);
        }
    }

    private void selfCheck() {
        StartupCheck.checkParameterNames(services);
    }

    private void fireStart() {
        for (Consumer<Aura> hook : startHooks) {
            hook.accept(this);
        }
    }

    private void loadConfig(String file) {
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(file)) {
            if (is == null) return;
            applyProperties(is);
        } catch (Exception e) {
            log.error("Failed to load {}", file, e);
        }
    }

    private void loadExternalConfig(String path) {
        try (InputStream is = new FileInputStream(path)) {
            applyProperties(is);
        } catch (Exception e) {
            log.error("Failed to load external config: {}", path, e);
        }
    }

    private void applyProperties(InputStream is) throws Exception {
        Properties p = new Properties();
        p.load(is);
        p.forEach((k, v) -> props.put(k.toString(), v.toString()));
        applyFrameworkProps();
    }

    private void applyFrameworkProps() {
        // env var > config file; helper reads env first, falls back to props map
        String port = resolve("aura.port", "AURA_PORT");
        if (port != null) this.port(Integer.parseInt(port));

        String env = resolve("aura.env", "AURA_ENV");
        if (env != null) this.env(env);

        String workers = resolve("aura.workers", "AURA_WORKERS");
        if (workers != null) this.workers(Integer.parseInt(workers));

        String cors = resolve("aura.cors", "AURA_CORS");
        if (cors != null) this.corsOrigin = cors.equals("true") ? "*" : cors;

        String maxBody = resolve("aura.max-body-size", "AURA_MAX_BODY_SIZE");
        if (maxBody != null) this.maxBodySize(Long.parseLong(maxBody));

        String maxUpload = resolve("aura.max-upload-size", "AURA_MAX_UPLOAD_SIZE");
        if (maxUpload != null) this.maxUploadSize(Long.parseLong(maxUpload));

        String shutdown = resolve("aura.shutdown-timeout", "AURA_SHUTDOWN_TIMEOUT");
        if (shutdown != null) this.shutdownTimeout(Integer.parseInt(shutdown));

        String accessLog = resolve("aura.access-log", "AURA_ACCESS_LOG");
        if (accessLog != null) {
            if ("true".equalsIgnoreCase(accessLog)) this.accessLogFormat = "text";
            else if ("false".equalsIgnoreCase(accessLog)) this.accessLogFormat = null;
            else this.accessLogFormat = accessLog;
        }

        String requestTimeout = resolve("aura.request-timeout", "AURA_REQUEST_TIMEOUT");
        if (requestTimeout != null) this.requestTimeout(Integer.parseInt(requestTimeout));

        String gzip = resolve("aura.gzip", "AURA_GZIP");
        if (gzip != null) this.gzip = "true".equalsIgnoreCase(gzip);

        String gzipMinSize = resolve("aura.gzip-min-size", "AURA_GZIP_MIN_SIZE");
        if (gzipMinSize != null) this.gzipMinSize = Math.max(0, Integer.parseInt(gzipMinSize));

        String jwtSecret = resolve("aura.jwt-secret", "AURA_JWT_SECRET");
        if (jwtSecret != null && jwtSupport == null) {
            String jwtExpire = resolve("aura.jwt-expire", "AURA_JWT_EXPIRE");
            jwt(jwtSecret, jwtExpire != null ? Long.parseLong(jwtExpire) : 604800);
        }
        validateJwtConfiguration();
    }

    private void validateJwtConfiguration() {
        if (jwtSupport != null) validateJwtConfiguration(jwtSecret, jwtExpireSeconds);
    }

    private void validateJwtConfiguration(String secret, long expireSeconds) {
        if ("dev".equalsIgnoreCase(env)) return;
        if (expireSeconds <= 0) {
            throw new IllegalArgumentException("JWT expiration must be positive outside development");
        }
        if (secret == null || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 bytes outside development");
        }
    }

    private String resolve(String propKey, String envKey) {
        String envVal = System.getenv(envKey);
        if (envVal != null) return envVal;
        String sysProp = System.getProperty(propKey);
        if (sysProp != null) return sysProp;
        return props.get(propKey);
    }

    private void fireStop() {
        for (int i = stopHooks.size() - 1; i >= 0; i--) {
            try {
                stopHooks.get(i).accept(this);
            } catch (Exception e) {
                log.error("Error in stop hook", e);
            }
        }
    }
}
