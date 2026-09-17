package io.aura;

import io.aura.snapshot.SnapshotOptions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;

/** Central, validated runtime configuration for an Aura application. */
public final class AuraConfig {
    public static final long DEFAULT_MAX_BODY_SIZE = 10L * 1024 * 1024;
    public static final long DEFAULT_MAX_UPLOAD_SIZE = 1024L * 1024;

    /** Loads framework settings from a properties file before application creation. */
    public static AuraConfig fromProperties(Path path) throws IOException {
        if (path == null) throw new IllegalArgumentException("config path must not be null");
        try (InputStream input = Files.newInputStream(path)) {
            Properties properties = new Properties();
            properties.load(input);
            return fromProperties(properties);
        }
    }

    /**
     * Creates validated framework configuration from properties. Environment and
     * JVM properties intentionally win over file values so deployments can use
     * one image with environment-specific settings.
     */
    public static AuraConfig fromProperties(Properties properties) {
        AuraConfig config = new AuraConfig();
        if (properties == null) return config;
        properties.forEach((key, value) -> {
            if (key != null && key.toString().startsWith("aura.") && value != null) {
                config.property(key.toString(), value.toString());
            }
        });
        config.port(parseInt(value(properties, "aura.port", "AURA_PORT"), config.port, "aura.port"));
        String env = value(properties, "aura.env", "AURA_ENV");
        if (env != null) config.env(env);
        config.workers(parseInt(value(properties, "aura.workers", "AURA_WORKERS"), config.workers, "aura.workers"));
        config.maxBodySize(parseLong(value(properties, "aura.max-body-size", "AURA_MAX_BODY_SIZE"), config.maxBodySize, "aura.max-body-size"));
        config.maxUploadSize(parseLong(value(properties, "aura.max-upload-size", "AURA_MAX_UPLOAD_SIZE"), config.maxUploadSize, "aura.max-upload-size"));
        config.shutdownTimeout(parseInt(value(properties, "aura.shutdown-timeout", "AURA_SHUTDOWN_TIMEOUT"), config.shutdownTimeout, "aura.shutdown-timeout"));
        config.requestTimeout(parseInt(value(properties, "aura.request-timeout", "AURA_REQUEST_TIMEOUT"), config.requestTimeout, "aura.request-timeout"));
        config.healthCheckTimeout(parseInt(value(properties, "aura.health-check-timeout", "AURA_HEALTH_CHECK_TIMEOUT"), config.healthCheckTimeout, "aura.health-check-timeout"));
        config.pageSizeMax(parseInt(value(properties, "aura.page-size-max", "AURA_PAGE_SIZE_MAX"), config.pageSizeMax, "aura.page-size-max"));
        config.concurrencyLimit(parseInt(value(properties, "aura.concurrency-limit", "AURA_CONCURRENCY_LIMIT"), config.concurrencyLimit, "aura.concurrency-limit"));
        config.rateLimitPerSecond(parseInt(value(properties, "aura.rate-limit-per-second", "AURA_RATE_LIMIT_PER_SECOND"), config.rateLimitPerSecond, "aura.rate-limit-per-second"));

        String publicOrigin = value(properties, "aura.public-origin", "AURA_PUBLIC_ORIGIN");
        if (publicOrigin != null) config.publicOrigin(publicOrigin);
        String allowedHosts = value(properties, "aura.allowed-hosts", "AURA_ALLOWED_HOSTS");
        if (allowedHosts != null) config.allowedHosts(allowedHosts.split(","));
        String securityHeaders = value(properties, "aura.security-headers", "AURA_SECURITY_HEADERS");
        if (securityHeaders != null) config.securityHeaders(parseBoolean(securityHeaders, "aura.security-headers"));
        String openApi = value(properties, "aura.openapi", "AURA_OPENAPI");
        if (openApi != null) config.openApi(parseBoolean(openApi, "aura.openapi"));
        String cors = value(properties, "aura.cors", "AURA_CORS");
        if (cors != null) config.cors("true".equalsIgnoreCase(cors) ? "*" : cors);
        String staticFiles = value(properties, "aura.static-files", "AURA_STATIC_FILES");
        if (staticFiles != null) config.staticFiles(staticFiles);
        String spa = value(properties, "aura.spa", "AURA_SPA");
        if (spa != null) config.spa(parseBoolean(spa, "aura.spa"));
        String accessLog = value(properties, "aura.access-log", "AURA_ACCESS_LOG");
        if (accessLog != null) config.accessLog("true".equalsIgnoreCase(accessLog) ? "text" :
                "false".equalsIgnoreCase(accessLog) ? null : accessLog);
        String gzip = value(properties, "aura.gzip", "AURA_GZIP");
        if (gzip != null) config.gzip(parseBoolean(gzip, "aura.gzip"));
        config.gzipMinSize(parseInt(value(properties, "aura.gzip-min-size", "AURA_GZIP_MIN_SIZE"), config.gzipMinSize, "aura.gzip-min-size"));
        return config.validate();
    }

    private static String value(Properties properties, String key, String envKey) {
        String value = System.getenv(envKey);
        if (value != null && !value.isBlank()) return value.trim();
        value = System.getProperty(key);
        if (value != null && !value.isBlank()) return value.trim();
        value = properties.getProperty(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int parseInt(String value, int fallback, String key) {
        if (value == null) return fallback;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException error) { throw new IllegalArgumentException(key + " must be an integer", error); }
    }

    private static long parseLong(String value, long fallback, String key) {
        if (value == null) return fallback;
        try { return Long.parseLong(value); }
        catch (NumberFormatException error) { throw new IllegalArgumentException(key + " must be a long integer", error); }
    }

    private static boolean parseBoolean(String value, String key) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException(key + " must be true or false");
    }

    private int port = 8080;
    private String env = "dev";
    private int workers = 200;
    private long maxBodySize = DEFAULT_MAX_BODY_SIZE;
    private long maxUploadSize = DEFAULT_MAX_UPLOAD_SIZE;
    private int shutdownTimeout = 30;
    private int requestTimeout;
    private int healthCheckTimeout = 1;
    private int pageSizeMax = 500;
    private int concurrencyLimit;
    private int rateLimitPerSecond;
    private String publicOrigin;
    private final Set<String> allowedHosts = new LinkedHashSet<>();
    private boolean securityHeaders = true;
    private boolean openApiEnabled;
    private String keyStorePath;
    private String keyStorePassword;
    private String staticFilesPath;
    private boolean spa;
    private String corsOrigin;
    private CorsConfig corsConfig;
    private boolean accessLog;
    private String accessLogFormat;
    private boolean gzip;
    private int gzipMinSize = 1024;
    private final Map<String, String> properties = new LinkedHashMap<>();
    private final JsonConfig jsonConfig = new JsonConfig();
    private final SnapshotOptions snapshotOptions = new SnapshotOptions();

    public AuraConfig port(int value) { port = value; return this; }
    public AuraConfig env(String value) { env = requireText(value, "env"); return this; }
    public AuraConfig workers(int value) { workers = value; return this; }
    public AuraConfig maxBodySize(long value) { maxBodySize = value; return this; }
    public AuraConfig maxUploadSize(long value) { maxUploadSize = value; return this; }
    public AuraConfig shutdownTimeout(int value) { shutdownTimeout = value; return this; }
    public AuraConfig requestTimeout(int value) { requestTimeout = value; return this; }
    public AuraConfig healthCheckTimeout(int value) { healthCheckTimeout = value; return this; }
    public AuraConfig pageSizeMax(int value) { pageSizeMax = value; return this; }
    public AuraConfig concurrencyLimit(int value) { concurrencyLimit = value; return this; }
    public AuraConfig rateLimitPerSecond(int value) { rateLimitPerSecond = value; return this; }
    public AuraConfig publicOrigin(String value) { publicOrigin = blankToNull(value); return this; }
    public AuraConfig allowedHosts(String... hosts) {
        allowedHosts.clear();
        if (hosts != null) for (String host : hosts) if (host != null && !host.isBlank()) allowedHosts.add(host.trim().toLowerCase());
        return this;
    }
    public AuraConfig securityHeaders(boolean value) { securityHeaders = value; return this; }
    public AuraConfig openApi(boolean value) { openApiEnabled = value; return this; }
    public AuraConfig tls(String keyStore, String password) {
        keyStorePath = requireText(keyStore, "TLS keystore path");
        keyStorePassword = password;
        return this;
    }

    public AuraConfig staticFiles(String path) { staticFilesPath = blankToNull(path); return this; }
    public AuraConfig spa(boolean value) { spa = value; return this; }
    public AuraConfig cors(boolean enabled) { corsOrigin = enabled ? "*" : null; corsConfig = null; return this; }
    public AuraConfig cors(String origin) { corsOrigin = blankToNull(origin); corsConfig = null; return this; }
    public AuraConfig cors(Consumer<CorsConfig> consumer) {
        if (consumer == null) throw new IllegalArgumentException("cors config consumer must not be null");
        CorsConfig value = new CorsConfig();
        consumer.accept(value);
        if (value.credentials() && value.hasWildcardOrigin()) {
            throw new IllegalArgumentException("Credentialed CORS requires explicit origins; wildcard '*' is not allowed");
        }
        corsConfig = value;
        corsOrigin = "__cors_config__";
        return this;
    }
    public AuraConfig accessLog(boolean enabled) { accessLog = enabled; accessLogFormat = enabled ? "text" : null; return this; }
    public AuraConfig accessLog(String format) { accessLogFormat = blankToNull(format); accessLog = accessLogFormat != null; return this; }
    public AuraConfig gzip(boolean enabled) { gzip = enabled; return this; }
    public AuraConfig gzipMinSize(int bytes) { gzipMinSize = Math.max(0, bytes); return this; }
    public AuraConfig jsonConfig(Consumer<JsonConfig> consumer) {
        if (consumer == null) throw new IllegalArgumentException("json config consumer must not be null");
        consumer.accept(jsonConfig);
        return this;
    }
    public AuraConfig snapshot(Consumer<SnapshotOptions> consumer) {
        if (consumer == null) throw new IllegalArgumentException("snapshot config consumer must not be null");
        consumer.accept(snapshotOptions);
        return this;
    }

    /** Stores a module-scoped property for resolution by an explicitly installed module. */
    public AuraConfig property(String key, String value) {
        if (key == null || !key.startsWith("aura.")) {
            throw new IllegalArgumentException("AuraConfig properties must use the aura.<module>.* namespace");
        }
        if (value == null) properties.remove(key); else properties.put(key, value);
        return this;
    }

    Map<String, String> properties() { return Map.copyOf(properties); }

    public int port() { return port; }
    public String env() { return env; }
    public int workers() { return workers; }
    public long maxBodySize() { return maxBodySize; }
    public long maxUploadSize() { return maxUploadSize; }
    public int shutdownTimeout() { return shutdownTimeout; }
    public int requestTimeout() { return requestTimeout; }
    public int healthCheckTimeout() { return healthCheckTimeout; }
    public int pageSizeMax() { return pageSizeMax; }
    public int concurrencyLimit() { return concurrencyLimit; }
    public int rateLimitPerSecond() { return rateLimitPerSecond; }
    public String publicOrigin() { return publicOrigin; }
    public Set<String> allowedHosts() { return Set.copyOf(allowedHosts); }
    public boolean securityHeaders() { return securityHeaders; }
    public boolean openApiEnabled() { return openApiEnabled; }
    public boolean tlsEnabled() { return keyStorePath != null; }
    public String keyStorePath() { return keyStorePath; }
    String keyStorePassword() { return keyStorePassword; }
    public String staticFilesPath() { return staticFilesPath; }
    public boolean spa() { return spa; }
    public String corsOrigin() { return corsOrigin; }
    public CorsConfig corsConfig() { return corsConfig; }
    public boolean accessLog() { return accessLog; }
    public String accessLogFormat() { return accessLogFormat; }
    public boolean gzip() { return gzip; }
    public int gzipMinSize() { return gzipMinSize; }
    public JsonConfig jsonConfig() { return jsonConfig; }
    public SnapshotOptions snapshotOptions() { return snapshotOptions; }

    /** Captures a validated, immutable view for a runtime or adapter. */
    public AuraConfigSnapshot snapshotView() { return AuraConfigSnapshot.from(this); }

    /** Validate before binding a listener; zero means disabled only for optional admission controls. */
    public AuraConfig validate() {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("port must be between 0 and 65535");
        if (workers < 1) throw new IllegalArgumentException("workers must be positive");
        if (maxBodySize <= 0) throw new IllegalArgumentException("maxBodySize must be positive and bounded");
        if (maxUploadSize <= 0) throw new IllegalArgumentException("maxUploadSize must be positive and bounded");
        if (maxUploadSize > maxBodySize) throw new IllegalArgumentException("maxUploadSize cannot exceed maxBodySize");
        if (shutdownTimeout < 0) throw new IllegalArgumentException("shutdownTimeout cannot be negative");
        if (requestTimeout < 0) throw new IllegalArgumentException("requestTimeout cannot be negative");
        if (healthCheckTimeout < 1 || healthCheckTimeout > 300) {
            throw new IllegalArgumentException("healthCheckTimeout must be between 1 and 300 seconds");
        }
        if (pageSizeMax < 1 || pageSizeMax > 100_000) throw new IllegalArgumentException("pageSizeMax is out of range");
        if (concurrencyLimit < 0 || rateLimitPerSecond < 0) throw new IllegalArgumentException("admission limits cannot be negative");
        if (tlsEnabled() && (keyStorePassword == null || keyStorePassword.isEmpty())) throw new IllegalArgumentException("TLS keystore password is required");
        return this;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
