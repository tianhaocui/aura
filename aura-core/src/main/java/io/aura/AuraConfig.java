package io.aura;

import java.util.LinkedHashSet;
import java.util.Set;

/** Central, validated runtime configuration for an Aura application. */
public final class AuraConfig {
    public static final long DEFAULT_MAX_BODY_SIZE = 10L * 1024 * 1024;
    public static final long DEFAULT_MAX_UPLOAD_SIZE = 1024L * 1024;

    private int port = 8080;
    private String env = "dev";
    private int workers = 200;
    private long maxBodySize = DEFAULT_MAX_BODY_SIZE;
    private long maxUploadSize = DEFAULT_MAX_UPLOAD_SIZE;
    private int shutdownTimeout = 30;
    private int requestTimeout;
    private int pageSizeMax = 500;
    private int concurrencyLimit;
    private int rateLimitPerSecond;
    private String publicOrigin;
    private final Set<String> allowedHosts = new LinkedHashSet<>();
    private boolean securityHeaders = true;
    private String keyStorePath;
    private String keyStorePassword;

    public AuraConfig port(int value) { port = value; return this; }
    public AuraConfig env(String value) { env = requireText(value, "env"); return this; }
    public AuraConfig workers(int value) { workers = value; return this; }
    public AuraConfig maxBodySize(long value) { maxBodySize = value; return this; }
    public AuraConfig maxUploadSize(long value) { maxUploadSize = value; return this; }
    public AuraConfig shutdownTimeout(int value) { shutdownTimeout = value; return this; }
    public AuraConfig requestTimeout(int value) { requestTimeout = value; return this; }
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
    public AuraConfig tls(String keyStore, String password) {
        keyStorePath = requireText(keyStore, "TLS keystore path");
        keyStorePassword = password;
        return this;
    }

    public int port() { return port; }
    public String env() { return env; }
    public int workers() { return workers; }
    public long maxBodySize() { return maxBodySize; }
    public long maxUploadSize() { return maxUploadSize; }
    public int shutdownTimeout() { return shutdownTimeout; }
    public int requestTimeout() { return requestTimeout; }
    public int pageSizeMax() { return pageSizeMax; }
    public int concurrencyLimit() { return concurrencyLimit; }
    public int rateLimitPerSecond() { return rateLimitPerSecond; }
    public String publicOrigin() { return publicOrigin; }
    public Set<String> allowedHosts() { return Set.copyOf(allowedHosts); }
    public boolean securityHeaders() { return securityHeaders; }
    public boolean tlsEnabled() { return keyStorePath != null; }
    public String keyStorePath() { return keyStorePath; }
    public String keyStorePassword() { return keyStorePassword; }

    /** Validate before binding a listener; zero means disabled only for optional admission controls. */
    public AuraConfig validate() {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("port must be between 0 and 65535");
        if (workers < 1) throw new IllegalArgumentException("workers must be positive");
        if (maxBodySize <= 0) throw new IllegalArgumentException("maxBodySize must be positive and bounded");
        if (maxUploadSize <= 0) throw new IllegalArgumentException("maxUploadSize must be positive and bounded");
        if (maxUploadSize > maxBodySize) throw new IllegalArgumentException("maxUploadSize cannot exceed maxBodySize");
        if (shutdownTimeout < 0) throw new IllegalArgumentException("shutdownTimeout cannot be negative");
        if (requestTimeout < 0) throw new IllegalArgumentException("requestTimeout cannot be negative");
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
