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
    private final List<AuraPlugin> plugins = new ArrayList<>();
    private String staticFilesPath;
    private boolean spaMode;
    private String corsOrigin;
    private long maxBodySize = 10 * 1024 * 1024; // 10MB default
    private int shutdownTimeout = 30;
    private int mcpPort = -1;
    private java.io.PrintStream mcpStdout;
    private McpRouterSpec mcpRouter;
    private final java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean(false);

    private AuraStarter starter;
    private McpStarter mcpStarter;

    private Aura() {
        loadConfig("aura.properties");
        applyFrameworkProps(); // apply env vars even if no config file
    }

    public static Aura create() {
        return new Aura();
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

    public Aura maxBodySize(long bytes) {
        this.maxBodySize = bytes;
        return this;
    }

    public Aura shutdownTimeout(int seconds) {
        this.shutdownTimeout = seconds;
        return this;
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

    public Aura onStart(Consumer<Aura> hook) {
        startHooks.add(hook);
        return this;
    }

    public Aura onStop(Consumer<Aura> hook) {
        stopHooks.add(hook);
        return this;
    }


    public String prop(String key) {
        return resolve(key, key.replace('.', '_').toUpperCase());
    }

    public int prop(String key, int defaultValue) {
        String val = prop(key);
        if (val == null) return defaultValue;
        return Integer.parseInt(val);
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
            } else if (arg.startsWith("--") && arg.contains("=")) {
                // --key=value → props, then re-apply framework settings
                int eq = arg.indexOf('=');
                String key = arg.substring(2, eq);
                String val = arg.substring(eq + 1);
                props.put(key, val);
            }
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
        for (AuraPlugin plugin : plugins) {
            plugin.install(this);
        }
        ServiceLoader<AuraStarter> loader = ServiceLoader.load(AuraStarter.class);
        starter = loader.findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No AuraStarter found. Add aura-web to your dependencies."));
        fireStart();
        starter.start(this);

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
    }

    public void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        if (mcpStarter != null) {
            mcpStarter.stop();
            mcpStarter = null;
        }
        if (starter != null) {
            starter.stop();
            starter = null;
        }
        fireStop();
    }


    public int port() { return port; }
    public String env() { return env; }
    public int workers() { return workers; }
    public Consumer<?> routeConfig() { return routeConfig; }
    public List<Object> services() { return services; }
    public List<RouteEntry> directRoutes() { return directRoutes; }
    public List<String> scanPackages() { return scanPackages; }
    public String staticFilesPath() { return staticFilesPath; }
    public boolean spa() { return spaMode; }
    public String corsOrigin() { return corsOrigin; }
    public long maxBodySize() { return maxBodySize; }
    public int shutdownTimeout() { return shutdownTimeout; }
    public int mcpPort() { return mcpPort; }
    public java.io.PrintStream mcpStdout() { return mcpStdout; }
    public McpRouterSpec mcpRouter() { return mcpRouter; }

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
    }

    private String resolve(String propKey, String envKey) {
        String envVal = System.getenv(envKey);
        if (envVal != null) return envVal;
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
