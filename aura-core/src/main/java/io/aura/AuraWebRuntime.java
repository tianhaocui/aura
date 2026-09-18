package io.aura;

import io.aura.contract.McpRouterSpec;
import io.aura.snapshot.SnapshotOptions;
import io.aura.web.BaseExceptionHandler;
import io.aura.web.BaseHandler;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Stable runtime contract consumed by the optional HTTP adapter.
 *
 * <p>The contract keeps transport configuration and registered contributions
 * behind the runtime boundary. A Web adapter must not depend on an application
 * facade implementation.</p>
 */
public interface AuraWebRuntime extends AuraMcpRuntime {
    /** Returns the initialized TLS context without exposing keystore credentials. */
    default javax.net.ssl.SSLContext tlsContext() { return null; }
    int port();
    String env();
    int workers();
    Consumer<?> routeConfig();
    List<Object> services();
    List<RouteEntry> directRoutes();
    List<WebSocketContribution> webSocketContributions();
    List<String> scanPackages();
    List<Class<?>> serviceClasses();
    String staticFilesPath();
    boolean spa();
    String corsOrigin();
    CorsConfig corsConfig();
    long maxBodySize();
    long maxUploadSize();
    long maxWebSocketMessageSize();
    default int maxWebSocketConnections() { return config().maxWebSocketConnections(); }
    default int webSocketIdleTimeout() { return config().webSocketIdleTimeout(); }
    int shutdownTimeout();
    boolean accessLog();
    String accessLogFormat();
    int requestTimeout();
    boolean gzip();
    int gzipMinSize();
    JsonConfig jsonConfig();
    Set<String> allowedHosts();
    Set<String> trustedProxies();
    boolean securityHeaders();
    boolean openApiEnabled();
    boolean devMode();
    int concurrencyLimit();
    int rateLimitPerSecond();
    int rateLimitMaxKeys();
    String publicOrigin();
    boolean isShuttingDown();
    List<io.aura.contract.HealthCheck> healthChecks();
    io.aura.contract.HealthStatus healthStatus();
    void emit(AuraEvent event);
    String prop(String key);
    <T> T getBean(Class<T> type);
    Function<io.aura.web.BaseContext, String> authFunction();
    java.util.function.BiPredicate<String, String> permissionChecker();
    Map<Class<? extends Exception>, BaseExceptionHandler<?>> exceptionHandlers();
    List<BaseHandler> beforeHandlers();
    List<BaseHandler> afterHandlers();
    Function<Object, Object> resultWrapper();
    io.aura.web.AdmissionPolicy admissionPolicy();
    SnapshotOptions snapshotOptions();
}
