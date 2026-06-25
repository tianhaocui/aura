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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Aura {

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
    private String staticFilesPath;
    private boolean spaMode;
    private String corsOrigin;
    private CorsConfig corsConfig;
    private long maxBodySize = 10 * 1024 * 1024; // 10MB default
    private int shutdownTimeout = 30;
    private String accessLogFormat;
    private int requestTimeout;
    private boolean gzip;
    private int gzipMinSize = 1024;
    private JsonConfig jsonConfig = new JsonConfig();
    private int mcpPort = -1;
    private java.io.PrintStream mcpStdout;
    private McpRouterSpec mcpRouter;
    private java.util.function.Function<io.aura.web.BaseContext, String> authFunction;
    private JwtSupport jwtSupport;
    private final java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile boolean shuttingDown;
    private volatile Thread keepAliveThread;
    private final java.util.LinkedHashMap<Class<? extends Exception>, io.aura.web.BaseExceptionHandler<?>> exceptionHandlers = new java.util.LinkedHashMap<>();
    private final List<io.aura.web.BaseHandler> beforeHandlers = new ArrayList<>();
    private final List<io.aura.web.BaseHandler> afterHandlers = new ArrayList<>();
    private java.util.function.Function<Object, Object> resultWrapper;

    private AuraStarter starter;
    private McpStarter mcpStarter;
    private boolean reloadMode;

    private Aura() {
        loadConfig("aura.properties");
        applyFrameworkProps();
        loadConfig("aura-" + env + ".properties");
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
        this.port = port;
        return this;
    }

    public Aura env(String env) {
        this.env = env;
        return this;
    }

    public Aura workers(int workers) {
        this.workers = workers;
        return this;
    }

    public Aura set(String key, String value) {
        props.put(key, value);
        return this;
    }

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
        this.corsConfig = c;
        this.corsOrigin = "__cors_config__";
        return this;
    }

    public Aura maxBodySize(long bytes) {
        this.maxBodySize = bytes;
        return this;
    }

    public Aura shutdownTimeout(int seconds) {
        this.shutdownTimeout = seconds;
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
        return this;
    }

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
        this.requestTimeout = Math.max(0, seconds);
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

    public Aura jwt(String secret) {
        return jwt(secret, 604800);
    }

    public Aura jwt(String secret, long expireSeconds) {
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
            Aura app = (Aura) ctx.get("_app", Object.class);
            if (app == null || app.authFunction == null) {
                throw new IllegalStateException("Auth not configured. Call app.auth() or app.jwt() first.");
            }
            String userId = app.authFunction.apply(ctx);
            if (userId == null) {
                ctx.status(401);
                ctx.json(java.util.Map.of("error", "Unauthorized"));
                ctx.abort();
            } else {
                ctx.set("_userId", userId);
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

    public Aura mcp(boolean enabled) {
        this.mcpPort = enabled ? 0 : -1;
        return this;
    }

    public Aura mcp(int port) {
        this.mcpPort = port;
        return this;
    }

    public Aura mcp(McpRouterSpec mcpRouter) {
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


    public void start(String[] args) {
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

    public void start() {
        if (reloadMode) {
            reloadMode = false;
            selfCheck();
            starter.reloadRoutes(this);
            return;
        }
        ServiceLoader.load(AuraPlugin.class).forEach(p -> {
            if (!plugins.contains(p)) plugins.add(p);
        });
        for (AuraPlugin plugin : plugins) {
            plugin.install(this);
        }
        registry.putIfAbsent(Aura.class, this);
        if (!serviceClasses.isEmpty()) {
            List<Object> resolved = ServiceResolver.resolve(serviceClasses, registry, namedRegistry);
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
        starter.start(this);
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
    }

    public void stop() {
        if (!stopped.compareAndSet(false, true)) return;
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
        for (int i = closeables.size() - 1; i >= 0; i--) {
            try { closeables.get(i).close(); }
            catch (Exception e) { log.error("Error closing {}", closeables.get(i).getClass().getSimpleName(), e); }
        }
        fireStop();
    }

    public void reloadConfig() {
        for (Reloadable r : reloadables) {
            try { r.reload(this); }
            catch (Exception e) { log.error("[Aura] Reload failed: {}", r.getClass().getSimpleName(), e); }
        }
    }


    public int port() { return port; }
    public String env() { return env; }
    public int workers() { return workers; }
    public Consumer<?> routeConfig() { return routeConfig; }
    public List<Object> services() { return services; }
    public List<RouteEntry> directRoutes() { return directRoutes; }
    public List<String> scanPackages() { return scanPackages; }
    public List<Class<?>> serviceClasses() { return serviceClasses; }
    public String staticFilesPath() { return staticFilesPath; }
    public boolean spa() { return spaMode; }
    public String corsOrigin() { return corsOrigin; }
    public CorsConfig corsConfig() { return corsConfig; }
    public long maxBodySize() { return maxBodySize; }
    public int shutdownTimeout() { return shutdownTimeout; }
    public boolean accessLog() { return accessLogFormat != null; }
    public String accessLogFormat() { return accessLogFormat; }
    public int requestTimeout() { return requestTimeout; }
    public boolean gzip() { return gzip; }
    public int gzipMinSize() { return gzipMinSize; }
    public JsonConfig jsonConfig() { return jsonConfig; }
    public int mcpPort() { return mcpPort; }
    public java.io.PrintStream mcpStdout() { return mcpStdout; }
    public McpRouterSpec mcpRouter() { return mcpRouter; }

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
        if (port != null) this.port = Integer.parseInt(port);

        String env = resolve("aura.env", "AURA_ENV");
        if (env != null) this.env = env;

        String workers = resolve("aura.workers", "AURA_WORKERS");
        if (workers != null) this.workers = Integer.parseInt(workers);

        String cors = resolve("aura.cors", "AURA_CORS");
        if (cors != null) this.corsOrigin = cors.equals("true") ? "*" : cors;

        String maxBody = resolve("aura.max-body-size", "AURA_MAX_BODY_SIZE");
        if (maxBody != null) this.maxBodySize = Long.parseLong(maxBody);

        String shutdown = resolve("aura.shutdown-timeout", "AURA_SHUTDOWN_TIMEOUT");
        if (shutdown != null) this.shutdownTimeout = Integer.parseInt(shutdown);

        String accessLog = resolve("aura.access-log", "AURA_ACCESS_LOG");
        if (accessLog != null) {
            if ("true".equalsIgnoreCase(accessLog)) this.accessLogFormat = "text";
            else if ("false".equalsIgnoreCase(accessLog)) this.accessLogFormat = null;
            else this.accessLogFormat = accessLog;
        }

        String requestTimeout = resolve("aura.request-timeout", "AURA_REQUEST_TIMEOUT");
        if (requestTimeout != null) this.requestTimeout = Math.max(0, Integer.parseInt(requestTimeout));

        String gzip = resolve("aura.gzip", "AURA_GZIP");
        if (gzip != null) this.gzip = "true".equalsIgnoreCase(gzip);

        String gzipMinSize = resolve("aura.gzip-min-size", "AURA_GZIP_MIN_SIZE");
        if (gzipMinSize != null) this.gzipMinSize = Math.max(0, Integer.parseInt(gzipMinSize));

        String jwtSecret = resolve("aura.jwt-secret", "AURA_JWT_SECRET");
        if (jwtSecret != null && jwtSupport == null) {
            String jwtExpire = resolve("aura.jwt-expire", "AURA_JWT_EXPIRE");
            jwt(jwtSecret, jwtExpire != null ? Long.parseLong(jwtExpire) : 604800);
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
