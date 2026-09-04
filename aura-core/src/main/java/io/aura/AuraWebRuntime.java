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
 * behind the runtime boundary. A Web adapter must not depend on the concrete
 * {@link Aura} builder implementation.</p>
 */
public interface AuraWebRuntime extends AuraMcpRuntime {
    int port();
    String env();
    int workers();
    Consumer<?> routeConfig();
    List<Object> services();
    List<RouteEntry> directRoutes();
    List<String> scanPackages();
    List<Class<?>> serviceClasses();
    String staticFilesPath();
    boolean spa();
    String corsOrigin();
    CorsConfig corsConfig();
    long maxBodySize();
    long maxUploadSize();
    int shutdownTimeout();
    boolean accessLog();
    String accessLogFormat();
    int requestTimeout();
    boolean gzip();
    int gzipMinSize();
    JsonConfig jsonConfig();
    Set<String> allowedHosts();
    boolean securityHeaders();
    boolean openApiEnabled();
    int concurrencyLimit();
    int rateLimitPerSecond();
    String publicOrigin();
    boolean isShuttingDown();
    void emit(AuraEvent event);
    String prop(String key);
    <T> T getBean(Class<T> type);
    Function<io.aura.web.BaseContext, String> authFunction();
    java.util.function.BiPredicate<String, String> permissionChecker();
    Map<Class<? extends Exception>, BaseExceptionHandler<?>> exceptionHandlers();
    List<BaseHandler> beforeHandlers();
    List<BaseHandler> afterHandlers();
    Function<Object, Object> resultWrapper();
    SnapshotOptions snapshotOptions();
}
