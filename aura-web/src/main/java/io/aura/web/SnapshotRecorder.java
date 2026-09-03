package io.aura.web;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import io.aura.Aura;
import io.aura.snapshot.ExceptionSnapshot;
import io.aura.snapshot.SnapshotOptions;
import io.aura.snapshot.SnapshotScope;
import io.undertow.server.HttpServerExchange;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

class SnapshotRecorder {

    private static final String TRUNCATION_MARKER = "...<truncated>";
    private static final String SAFE_PATH_PARAMETER = "redacted";
    private static final Map<Aura, SnapshotRecorder> RECORDERS = new ConcurrentHashMap<>();
    private static final Map<Path, Object> STORAGE_LOCKS = new ConcurrentHashMap<>();

    private final SnapshotOptions options;
    private final SnapshotRedactor redactor;
    private final ExceptionSnapshot.EnvSnapshot env;
    private final ArrayBlockingQueue<ExceptionSnapshot> queue;
    private final Thread writer;
    private volatile boolean closed;

    private SnapshotRecorder(Aura app) {
        this.options = app.snapshotOptions();
        this.redactor = new SnapshotRedactor(options);
        this.env = captureEnv(app, options);
        this.queue = options.async() ? new ArrayBlockingQueue<>(options.queueSize()) : null;
        this.writer = options.async() ? startWriter() : null;
    }

    static SnapshotRecorder of(Aura app) {
        return RECORDERS.computeIfAbsent(app, SnapshotRecorder::new);
    }

    static void close(Aura app) {
        SnapshotRecorder recorder = RECORDERS.remove(app);
        if (recorder != null) recorder.close();
    }

    static boolean shouldCapture(Aura app) {
        SnapshotOptions options = app.snapshotOptions();
        if (!options.enabled()) return false;
        return options.sampleRate() >= 1.0 || Math.random() <= options.sampleRate();
    }

    void record(Exception e, CompiledRoute route, Context ctx, HttpServerExchange exchange, SnapshotScope scope) {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach(h -> headers.put(h.getHeaderName().toString(), h.getFirst()));
        Map<String, String> query = new LinkedHashMap<>();
        exchange.getQueryParameters().forEach((k, v) -> query.put(k, v == null || v.isEmpty() ? "" : v.peek()));
        String body = options.bodyMode() == SnapshotOptions.BodyMode.CACHED_ONLY ? ctx.cachedBody() : null;
        Map<String, String> pathParams = route.match(stripQuery(ctx.url()));
        record(e, route.method(), route.rawPath(), pathParams, handlerName(route), ctx.method(),
                safeRequestPath(route, ctx.url(), pathParams),
                query, headers, body, ctx.ip(), ctx.requestId(), ctx.statusCode(), scope);
    }

    void record(Exception e, CompiledRoute route, MockContext ctx, String method, String path,
                Map<String, String> query, Map<String, String> headers, String body, SnapshotScope scope) {
        Map<String, String> pathParams = route.match(stripQuery(path));
        record(e, route.method(), route.rawPath(), pathParams, handlerName(route), method,
                safeRequestPath(route, path, pathParams),
                query, headers, body, ctx.ip(), ctx.requestId(), ctx.statusCode(), scope);
    }

    private String safeRequestPath(CompiledRoute route, String actualPath, Map<String, String> pathParams) {
        if (pathParams == null && actualPath != null) {
            int queryStart = actualPath.indexOf('?');
            if (queryStart >= 0) pathParams = route.match(actualPath.substring(0, queryStart));
        }
        if (pathParams == null || pathParams.isEmpty()) return redactor.path(actualPath);
        String safePath = route.rawPath();
        for (String name : pathParams.keySet()) {
            safePath = safePath.replace("{" + name + "}", SAFE_PATH_PARAMETER);
        }
        return redactor.path(safePath);
    }

    private static String stripQuery(String path) {
        if (path == null) return null;
        int queryStart = path.indexOf('?');
        return queryStart >= 0 ? path.substring(0, queryStart) : path;
    }

    private static Map<String, String> safePathParams(Map<String, String> pathParams) {
        if (pathParams == null || pathParams.isEmpty()) return Map.of();
        Map<String, String> safe = new LinkedHashMap<>();
        pathParams.keySet().forEach(key -> safe.put(key, "<redacted>"));
        return safe;
    }

    private void record(Exception e, String routeMethod, String routePath, Map<String, String> pathParams,
                        String handler, String method, String path, Map<String, String> query,
                        Map<String, String> headers, String body, String ip, String requestId,
                        int status, SnapshotScope scope) {
        Throwable cause = ExceptionResolver.unwrap(e);
        ExceptionSnapshot snapshot = new ExceptionSnapshot(
                1,
                Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID(),
                Instant.now().toString(),
                new ExceptionSnapshot.RequestSnapshot(method, redactor.path(path), redactor.query(query), redactor.headers(headers),
                        redactor.body(body, options.maxBodySize()), ip, requestId),
                new ExceptionSnapshot.RouteSnapshot(routeMethod, redactor.path(routePath),
                        safePathParams(pathParams), handler),
                new ExceptionSnapshot.ExceptionInfo(cause.getClass().getName(), null,
                        redactor.exceptionText(ExceptionResolver.code(cause)),
                        redactor.exceptionText(ExceptionResolver.field(cause)), ExceptionResolver.status(cause),
                        rootType(cause), null, stackTrace(cause)),
                response(status, scope),
                env,
                scope != null ? scope.externalCalls().stream().map(this::redactExternalCall).toList() : List.of(),
                scope != null ? redactArtifacts(scope.artifacts()) : Map.of()
        );
        write(snapshot);
    }

    private ExceptionSnapshot.ResponseSnapshot response(int status, SnapshotScope scope) {
        if (scope != null && scope.response() != null) {
            ExceptionSnapshot.ResponseSnapshot resp = scope.response();
            return new ExceptionSnapshot.ResponseSnapshot(resp.status(), redactor.headers(resp.headers()), resp.contentType(),
                    redactor.body(resp.body(), options.maxBodySize()));
        }
        return new ExceptionSnapshot.ResponseSnapshot(status, Map.of(), null, null);
    }

    private ExceptionSnapshot.ExternalCallSnapshot redactExternalCall(ExceptionSnapshot.ExternalCallSnapshot call) {
        return new ExceptionSnapshot.ExternalCallSnapshot(call.id(), call.name(), call.protocol(), call.method(), redactor.url(call.url()),
                redactor.headers(call.requestHeaders()),
                redactor.body(call.requestBody(), options.maxExternalBodySize()),
                call.responseStatus(),
                redactor.headers(call.responseHeaders()),
                redactor.body(call.responseBody(), options.maxExternalBodySize()),
                call.durationMs(), call.startedAt());
    }

    private Map<String, Object> redactArtifacts(Map<String, Object> artifacts) {
        Map<String, Object> result = new LinkedHashMap<>();
        artifacts.forEach((key, value) -> result.put(key,
                redactor.isSensitiveField(key) ? "<redacted>" : redactor.artifact(value)));
        return result;
    }

    private void write(ExceptionSnapshot snapshot) {
        if (closed) return;
        if (options.async()) {
            queue.offer(snapshot);
        } else {
            writeNow(snapshot);
        }
    }

    private Thread startWriter() {
        Thread t = new Thread(() -> {
            while (!closed || !queue.isEmpty()) {
                try {
                    ExceptionSnapshot snapshot = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (snapshot != null) writeNow(snapshot);
                } catch (InterruptedException e) {
                    if (!closed) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "aura-snapshot-writer");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void close() {
        closed = true;
        if (writer == null) return;
        writer.interrupt();
        boolean interrupted = false;
        while (writer.isAlive()) {
            try {
                writer.join(2_000L);
                if (writer.isAlive()) break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void writeNow(ExceptionSnapshot snapshot) {
        try {
            String json = JSON.toJSONString(snapshot, JSONWriter.Feature.PrettyFormat);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            if (options.maxSnapshotFileSize() <= 0 || bytes.length > options.maxSnapshotFileSize()) {
                return;
            }
            Path dir = options.dir().toAbsolutePath().normalize();
            Object storageLock = STORAGE_LOCKS.computeIfAbsent(dir, ignored -> new Object());
            synchronized (storageLock) {
                Files.createDirectories(dir);
                if (options.maxStorageSize() <= 0 || storageSize(dir) + bytes.length > options.maxStorageSize()) {
                    return;
                }
                Path path = dir.resolve(snapshot.snapshotId() + ".json");
                Files.write(path, bytes);
            }
        } catch (Exception ignored) {}
    }

    private static long storageSize(Path dir) {
        try (var files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .mapToLong(file -> {
                        try { return Files.size(file); } catch (Exception ignored) { return 0L; }
                    }).sum();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static ExceptionSnapshot.EnvSnapshot captureEnv(Aura app, SnapshotOptions options) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : options.envAllowlist()) {
            String value = System.getenv(key);
            if (value != null) env.put(key, value);
        }
        Map<String, String> props = new LinkedHashMap<>();
        props.put("aura.env", app.env());
        props.put("aura.port", String.valueOf(app.port()));
        props.put("aura.workers", String.valueOf(app.workers()));
        props.put("aura.request-timeout", String.valueOf(app.requestTimeout()));
        props.put("aura.gzip", String.valueOf(app.gzip()));
        return new ExceptionSnapshot.EnvSnapshot(
                Aura.class.getPackage().getImplementationVersion(),
                app.env(),
                System.getProperty("java.version"),
                System.getProperty("os.name"),
                java.util.TimeZone.getDefault().getID(),
                java.util.Locale.getDefault().toLanguageTag(),
                props,
                env
        );
    }

    private String stackTrace(Throwable t) {
        int maxSize = options.maxStackTraceSize();
        if (maxSize <= 0) return null;
        StringBuilder result = new StringBuilder();
        Throwable current = t;
        boolean truncated = false;
        while (current != null) {
            if (!appendWithinLimit(result, current.getClass().getName() + '\n', maxSize)) {
                truncated = true;
                break;
            }
            for (StackTraceElement frame : current.getStackTrace()) {
                if (!appendWithinLimit(result, "\\tat " + frame + '\n', maxSize)) {
                    truncated = true;
                    break;
                }
            }
            if (truncated) break;
            current = current.getCause();
            if (current != null && !appendWithinLimit(result, "Caused by: ", maxSize)) {
                truncated = true;
                break;
            }
        }
        return truncated ? addTruncationMarker(result, maxSize) : result.toString();
    }

    private static boolean appendWithinLimit(StringBuilder result, String value, int maxSize) {
        int remaining = maxSize - result.length();
        if (value.length() <= remaining) {
            result.append(value);
            return true;
        }
        result.append(value, 0, remaining);
        return false;
    }

    private static String addTruncationMarker(StringBuilder result, int maxSize) {
        if (maxSize <= TRUNCATION_MARKER.length()) {
            return TRUNCATION_MARKER.substring(0, maxSize);
        }
        int contentSize = maxSize - TRUNCATION_MARKER.length();
        return result.substring(0, Math.min(result.length(), contentSize)) + TRUNCATION_MARKER;
    }

    private static String rootType(Throwable t) {
        Throwable root = root(t);
        return root != null ? root.getClass().getName() : null;
    }

    private static String rootMessage(Throwable t) {
        Throwable root = root(t);
        return root != null ? root.getMessage() : null;
    }

    private static Throwable root(Throwable t) {
        Throwable cur = t;
        while (cur != null && cur.getCause() != null) cur = cur.getCause();
        return cur;
    }

    private static String handlerName(CompiledRoute route) {
        if (route.handler() instanceof MethodRefHandler mh && mh.resolvedMethod() != null) {
            var method = mh.resolvedMethod();
            return method.getDeclaringClass().getName() + "." + method.getName();
        }
        return route.handler().getClass().getName();
    }
}
