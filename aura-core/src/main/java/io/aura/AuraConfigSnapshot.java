package io.aura;

import io.aura.snapshot.SnapshotOptions;

import java.util.Set;

/**
 * Immutable configuration view captured by a runtime at construction time.
 *
 * <p>The builder-style {@link AuraConfig} remains mutable until it is handed
 * to an application. Runtime adapters only receive this snapshot, so changing
 * the original builder or a returned nested configuration cannot change a
 * running application.</p>
 */
public final class AuraConfigSnapshot {
    private final int port;
    private final String env;
    private final int workers;
    private final long maxBodySize;
    private final long maxUploadSize;
    private final int shutdownTimeout;
    private final int requestTimeout;
    private final int healthCheckTimeout;
    private final int pageSizeMax;
    private final int concurrencyLimit;
    private final int rateLimitPerSecond;
    private final int rateLimitMaxKeys;
    private final String publicOrigin;
    private final Set<String> allowedHosts;
    private final boolean securityHeaders;
    private final boolean openApiEnabled;
    private final boolean devMode;
    private final String keyStorePath;
    private final String staticFilesPath;
    private final boolean spa;
    private final String corsOrigin;
    private final CorsConfig corsConfig;
    private final boolean accessLog;
    private final String accessLogFormat;
    private final boolean gzip;
    private final int gzipMinSize;
    private final JsonConfig jsonConfig;
    private final SnapshotOptions snapshotOptions;

    private AuraConfigSnapshot(AuraConfig source) {
        source.validate();
        this.port = source.port();
        this.env = source.env();
        this.workers = source.workers();
        this.maxBodySize = source.maxBodySize();
        this.maxUploadSize = source.maxUploadSize();
        this.shutdownTimeout = source.shutdownTimeout();
        this.requestTimeout = source.requestTimeout();
        this.healthCheckTimeout = source.healthCheckTimeout();
        this.pageSizeMax = source.pageSizeMax();
        this.concurrencyLimit = source.concurrencyLimit();
        this.rateLimitPerSecond = source.rateLimitPerSecond();
        this.rateLimitMaxKeys = source.rateLimitMaxKeys();
        this.publicOrigin = source.publicOrigin();
        this.allowedHosts = Set.copyOf(source.allowedHosts());
        this.securityHeaders = source.securityHeaders();
        this.openApiEnabled = source.openApiEnabled();
        this.devMode = source.devMode();
        this.keyStorePath = source.keyStorePath();
        this.staticFilesPath = source.staticFilesPath();
        this.spa = source.spa();
        this.corsOrigin = source.corsOrigin();
        this.corsConfig = source.corsConfig() == null ? null : source.corsConfig().copy();
        this.accessLog = source.accessLog();
        this.accessLogFormat = source.accessLogFormat();
        this.gzip = source.gzip();
        this.gzipMinSize = source.gzipMinSize();
        this.jsonConfig = source.jsonConfig().copy();
        this.snapshotOptions = source.snapshotOptions().copy();
    }

    public static AuraConfigSnapshot from(AuraConfig source) {
        return new AuraConfigSnapshot(source == null ? new AuraConfig() : source);
    }

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
    public int rateLimitMaxKeys() { return rateLimitMaxKeys; }
    public String publicOrigin() { return publicOrigin; }
    public Set<String> allowedHosts() { return allowedHosts; }
    public boolean securityHeaders() { return securityHeaders; }
    public boolean openApiEnabled() { return openApiEnabled; }
    public boolean devMode() { return devMode; }
    public boolean tlsEnabled() { return keyStorePath != null; }
    public String keyStorePath() { return keyStorePath; }
    public String staticFilesPath() { return staticFilesPath; }
    public boolean spa() { return spa; }
    public String corsOrigin() { return corsOrigin; }
    public CorsConfig corsConfig() { return corsConfig == null ? null : corsConfig.copy(); }
    public boolean accessLog() { return accessLog; }
    public String accessLogFormat() { return accessLogFormat; }
    public boolean gzip() { return gzip; }
    public int gzipMinSize() { return gzipMinSize; }
    public JsonConfig jsonConfig() { return jsonConfig.copy(); }
    public SnapshotOptions snapshotOptions() { return snapshotOptions.copy(); }
}
