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

    private int port = Integer.parseInt(System.getenv().getOrDefault("AURA_PORT", "8080"));
    private String env = System.getenv().getOrDefault("AURA_ENV", "dev");
    private int workers = 200;
    private final Map<String, String> props = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> registry = new ConcurrentHashMap<>();
    private final List<Consumer<Aura>> startHooks = new ArrayList<>();
    private final List<Consumer<Aura>> stopHooks = new ArrayList<>();
    private Consumer<?> routeConfig;
    private final List<Object> services = new ArrayList<>();
    private final List<RouteEntry> directRoutes = new ArrayList<>();
    private final List<String> scanPackages = new ArrayList<>();
    private String staticFilesPath;
    private String corsOrigin;
    private long maxBodySize = 10 * 1024 * 1024; // 10MB default
    private int shutdownTimeout = 30;
    private int mcpPort = -1;
    private java.io.PrintStream mcpStdout;
    private AuraStarter starter;
    private McpStarter mcpStarter;

    private Aura() {
        loadConfig("aura.properties");
    }

    public static Aura create() {
        return new Aura();
    }

    // --- builder ---

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

    public Aura prop(String key, String value) {
        props.put(key, value);
        return this;
    }

    public Aura staticFiles(String path) {
        this.staticFilesPath = path;
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

    public <R> Aura routes(Consumer<R> routeConfig) {
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

    // --- props ---

    public String prop(String key) {
        String envVal = System.getenv(key.replace('.', '_').toUpperCase());
        if (envVal != null) return envVal;
        return props.get(key);
    }

    public int prop(String key, int defaultValue) {
        String val = prop(key);
        if (val == null) return defaultValue;
        return Integer.parseInt(val);
    }

    // --- registry ---

    public <T> Aura register(T instance) {
        registry.put(instance.getClass(), instance);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type) {
        for (Map.Entry<Class<?>, Object> entry : registry.entrySet()) {
            if (type.isAssignableFrom(entry.getKey())) {
                return (T) entry.getValue();
            }
        }
        return null;
    }

    // --- lifecycle ---

    public void start(String[] args) {
        boolean mcpStdio = false;
        for (String arg : args) {
            if (arg.startsWith("--config=")) {
                loadExternalConfig(arg.substring(9));
            } else if (arg.startsWith("--port=")) {
                this.port = Integer.parseInt(arg.substring(7));
            } else if (arg.startsWith("--env=")) {
                this.env = arg.substring(6);
            } else if (arg.startsWith("--scan=")) {
                scan(arg.substring(7).split(","));
            } else if ("--mcp-stdio".equals(arg)) {
                mcpStdio = true;
            }
        }
        if (mcpStdio) {
            this.mcpPort = -2;
            this.mcpStdout = System.out; // save real stdout before redirect
            System.setOut(System.err);
        }
        start();
    }

    public void start() {
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

    // --- accessors for starter ---

    public int port() { return port; }
    public String env() { return env; }
    public int workers() { return workers; }
    public Consumer<?> routeConfig() { return routeConfig; }
    public List<Object> services() { return services; }
    public List<RouteEntry> directRoutes() { return directRoutes; }
    public List<String> scanPackages() { return scanPackages; }
    public String staticFilesPath() { return staticFilesPath; }
    public String corsOrigin() { return corsOrigin; }
    public long maxBodySize() { return maxBodySize; }
    public int shutdownTimeout() { return shutdownTimeout; }
    public int mcpPort() { return mcpPort; }
    public java.io.PrintStream mcpStdout() { return mcpStdout; }

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
        if (props.containsKey("port")) this.port = Integer.parseInt(props.get("port"));
        if (props.containsKey("env")) this.env = props.get("env");
        if (props.containsKey("workers")) this.workers = Integer.parseInt(props.get("workers"));
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
