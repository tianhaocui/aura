package io.aura.web;

import com.alibaba.fastjson2.JSON;
import io.aura.snapshot.ExceptionSnapshot;
import io.aura.snapshot.Snapshot;
import io.aura.snapshot.SnapshotScope;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SnapshotHttpClient {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final int DEFAULT_MAX_RESPONSE_BODY_SIZE = 1024 * 1024;
    private static final ExecutorService RESPONSE_BODY_READERS = new ThreadPoolExecutor(
            0, 32, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(), new DaemonThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy());

    private final String name;
    private final URI baseUri;
    private final String baseDirectoryPath;
    private final HttpClient client;
    private final Duration requestTimeout;
    private final int maxResponseBodySize;

    public SnapshotHttpClient(String name, String baseUrl) {
        this(name, URI.create(baseUrl), HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build());
    }

    public SnapshotHttpClient(String name, URI baseUri, HttpClient client) {
        this(name, baseUri, client, DEFAULT_TIMEOUT, DEFAULT_MAX_RESPONSE_BODY_SIZE);
    }

    public SnapshotHttpClient(String name, URI baseUri, HttpClient client, Duration requestTimeout) {
        this(name, baseUri, client, requestTimeout, DEFAULT_MAX_RESPONSE_BODY_SIZE);
    }

    public SnapshotHttpClient(String name, URI baseUri, HttpClient client,
                              Duration requestTimeout, int maxResponseBodySize) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (baseUri == null || !baseUri.isAbsolute() || baseUri.getHost() == null
                || (!"http".equalsIgnoreCase(baseUri.getScheme()) && !"https".equalsIgnoreCase(baseUri.getScheme()))
                || baseUri.getUserInfo() != null || baseUri.getRawQuery() != null
                || baseUri.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "baseUri must be an absolute http(s) URI without userinfo, query, or fragment");
        }
        if (client == null) throw new IllegalArgumentException("client is required");
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("client must not follow redirects");
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (maxResponseBodySize <= 0) throw new IllegalArgumentException("maxResponseBodySize must be positive");
        this.name = name;
        this.baseUri = baseUri.normalize();
        this.baseDirectoryPath = baseDirectoryPath(this.baseUri);
        this.client = client;
        this.requestTimeout = requestTimeout;
        this.maxResponseBodySize = maxResponseBodySize;
    }

    public <T> T get(String path, Class<T> type) {
        return send("GET", path, null, Map.of(), type);
    }

    public <T> T post(String path, Object body, Class<T> type) {
        return send("POST", path, body, Map.of("Content-Type", "application/json"), type);
    }

    public <T> T send(String method, String path, Object body, Map<String, String> headers, Class<T> type) {
        URI uri = resolvePath(path);
        SnapshotScope scope = Snapshot.currentOrNull();
        if (scope != null && scope.replaying()) {
            ExceptionSnapshot.ExternalCallSnapshot call = scope.nextReplayExternalCall(name, method, uri.toString());
            if (call.responseStatus() >= 400) {
                String code = name.toUpperCase() + "_HTTP_" + call.responseStatus();
                String message = "External call failed: " + name + " " + call.responseStatus();
                if (call.responseBody() instanceof Map<?, ?> recordedBody) {
                    Object recordedCode = recordedBody.get("code");
                    Object recordedMessage = recordedBody.get("message");
                    if (recordedCode != null) code = String.valueOf(recordedCode);
                    if (recordedMessage != null) message = String.valueOf(recordedMessage);
                }
                throw new ApiException(call.responseStatus(), code, message);
            }
            return convert(call.responseBody(), type);
        }

        String requestBody = body == null ? null : body instanceof String s ? s : JSON.toJSONString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(requestTimeout);
        headers.forEach(builder::header);
        if ("GET".equalsIgnoreCase(method)) {
            builder.GET();
        } else {
            builder.method(method, requestBody == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(requestBody));
        }

        Instant started = Instant.now();
        long t0 = System.currentTimeMillis();
        long deadlineNanos = System.nanoTime() + requestTimeout.toNanos();
        try {
            HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            String responseBody;
            try (InputStream input = response.body()) {
                responseBody = readBoundedBeforeDeadline(input, maxResponseBodySize, deadlineNanos);
            }
            long elapsed = System.currentTimeMillis() - t0;
            if (scope != null) {
                Snapshot.externalCall(new ExceptionSnapshot.ExternalCallSnapshot(
                        UUID.randomUUID().toString(),
                        name,
                        "http",
                        method.toUpperCase(),
                        uri.toString(),
                        headers,
                        body,
                        response.statusCode(),
                        responseHeaders(response),
                        responseBody,
                        elapsed,
                        started.toString()
                ));
            }
            if (response.statusCode() >= 400) {
                throw httpError(response.statusCode());
            }
            return convert(responseBody, type);
        } catch (ApiException e) {
            throw e;
        } catch (TimeoutException e) {
            recordFailedCall(scope, method, uri, body, 504, name.toUpperCase() + "_TIMEOUT",
                    "External call timed out: " + name, e, started, t0);
            throw new ApiException(504, name.toUpperCase() + "_TIMEOUT",
                    "External call timed out: " + name, null, e);
        } catch (ResponseTooLargeException e) {
            recordFailedCall(scope, method, uri, body, 502, name.toUpperCase() + "_RESPONSE_TOO_LARGE",
                    "External response exceeded configured limit: " + name, e, started, t0);
            throw new ApiException(502, name.toUpperCase() + "_RESPONSE_TOO_LARGE",
                    "External response exceeded configured limit: " + name, null, e);
        } catch (Exception e) {
            recordFailedCall(scope, method, uri, body, 502, name.toUpperCase() + "_UNAVAILABLE",
                    "External call failed: " + name, e, started, t0);
            throw new ApiException(502, name.toUpperCase() + "_UNAVAILABLE",
                    "External call failed: " + name, null, e);
        }
    }

    private void recordFailedCall(SnapshotScope scope, String method, URI uri, Object body,
                                  int status, String code, String message, Exception error,
                                  Instant started, long t0) {
        if (scope == null || scope.replaying()) return;
        Snapshot.externalCall(new ExceptionSnapshot.ExternalCallSnapshot(
                UUID.randomUUID().toString(), name, "http", method.toUpperCase(), uri.toString(),
                Map.of(), body, status, Map.of(), Map.of("code", code, "message", message,
                        "error", error.getClass().getSimpleName()),
                System.currentTimeMillis() - t0, started.toString()));
    }

    private ApiException httpError(int status) {
        return new ApiException(status, name.toUpperCase() + "_HTTP_" + status,
                "External call failed: " + name + " " + status);
    }

    private URI resolvePath(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path is required");
        final URI relative;
        try {
            relative = URI.create(path);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid relative path", e);
        }
        if (relative.isAbsolute() || relative.getRawAuthority() != null) {
            throw new IllegalArgumentException("Only relative paths are allowed");
        }
        validatePath(relative);
        URI resolved = baseUri.resolve(relative).normalize();
        if (!baseUri.getScheme().equalsIgnoreCase(resolved.getScheme())
                || !baseUri.getHost().equalsIgnoreCase(resolved.getHost())
                || effectivePort(baseUri) != effectivePort(resolved)
                || resolved.getUserInfo() != null) {
            throw new IllegalArgumentException("Resolved URI is outside the configured base host");
        }
        if (!isWithinBaseDirectory(resolved.getPath())) {
            throw new IllegalArgumentException("Resolved URI is outside the configured base path");
        }
        return resolved;
    }

    private static void validatePath(URI relative) {
        if (relative.getRawFragment() != null) {
            throw new IllegalArgumentException("Fragments are not allowed");
        }
        String rawPath = relative.getRawPath();
        if (rawPath == null) return;
        String lowerPath = rawPath.toLowerCase(java.util.Locale.ROOT);
        if (rawPath.indexOf('\\') >= 0 || lowerPath.contains("%2f") || lowerPath.contains("%5c")) {
            throw new IllegalArgumentException("Encoded or backslash path separators are not allowed");
        }
        String decodedPath = relative.getPath();
        for (String segment : decodedPath.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Dot path segments are not allowed");
            }
        }
    }

    private static String baseDirectoryPath(URI baseUri) {
        String path = baseUri.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) return "/";
        return path.endsWith("/") ? path : path + "/";
    }

    private boolean isWithinBaseDirectory(String resolvedPath) {
        if (resolvedPath == null || !resolvedPath.startsWith("/")) return false;
        if ("/".equals(baseDirectoryPath)) return true;
        String basePath = baseDirectoryPath.substring(0, baseDirectoryPath.length() - 1);
        return resolvedPath.equals(basePath) || resolvedPath.startsWith(baseDirectoryPath);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String readBounded(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > (long) maxBytes - total) throw new ResponseTooLargeException();
            total += read;
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static String readBoundedBeforeDeadline(InputStream input, int maxBytes, long deadlineNanos)
            throws IOException, TimeoutException {
        Future<String> readTask;
        try {
            readTask = RESPONSE_BODY_READERS.submit(() -> readBounded(input, maxBytes));
        } catch (RejectedExecutionException e) {
            throw new IOException("External response reader capacity is exhausted", e);
        }
        try {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) throw new TimeoutException();
            return readTask.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading external response", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IOException("Could not read external response", cause);
        } catch (TimeoutException e) {
            readTask.cancel(true);
            try {
                input.close();
            } catch (IOException ignored) {
                // Closing the stream is best effort after the deadline expires.
            }
            throw e;
        }
    }

    private static final class ResponseTooLargeException extends IOException {}

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "aura-snapshot-http-reader");
            thread.setDaemon(true);
            return thread;
        }
    }

    private static Map<String, String> responseHeaders(HttpResponse<?> response) {
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach((k, v) -> headers.put(k, String.join(",", v)));
        return headers;
    }

    private static <T> T convert(Object body, Class<T> type) {
        if (type == Void.class) return null;
        if (type == String.class) return type.cast(body instanceof String s ? s : JSON.toJSONString(body));
        if (body == null) return null;
        if (body instanceof String s) return JSON.parseObject(s, type);
        return JSON.parseObject(JSON.toJSONString(body), type);
    }
}
