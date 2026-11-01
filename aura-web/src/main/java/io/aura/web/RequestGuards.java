package io.aura.web;

import com.alibaba.fastjson2.JSON;
import io.aura.AuraEvent;
import io.aura.AuraWebRuntime;
import io.aura.CorsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The pre-route policy chain: host allow-list, admission, security headers, CSRF, CORS.
 *
 * <p>Both entry points build it from {@link #standard}, so {@code TestClient} rejects exactly what
 * a real socket rejects instead of skipping straight to the handler.</p>
 *
 * <p>Draining is deliberately absent. Rejecting new traffic while in-flight requests finish is a
 * transport lifecycle job, and a per-request rule here would silence the {@code /health} probes
 * whose whole purpose is to answer {@code DOWN}.</p>
 */
public final class RequestGuards {

    private static final Logger log = LoggerFactory.getLogger(RequestGuards.class);

    private final List<RequestGuard> guards;

    private RequestGuards(List<RequestGuard> guards) {
        this.guards = guards;
    }

    /** Route template an admission policy should judge a request against. */
    @FunctionalInterface
    public interface RoutePattern {
        String matching(String method, String path);
    }

    /** The chain Aura's own HTTP adapter runs, in the order its checks have always run. */
    public static RequestGuards standard(AuraWebRuntime app, RoutePattern routePattern) {
        List<RequestGuard> chain = new ArrayList<>();
        chain.add(new HostGuard(app));
        chain.add(new AdmissionGuard(app, routePattern));
        chain.add(new SecurityHeadersGuard(app));
        chain.add(new CsrfGuard(app));
        chain.add(new CorsGuard(app));
        return new RequestGuards(List.copyOf(chain));
    }

    public GuardOutcome evaluate(GuardedRequest request) {
        for (RequestGuard guard : guards) {
            GuardOutcome outcome = guard.evaluate(request);
            if (!outcome.proceed()) return outcome;
        }
        return GuardOutcome.next();
    }

    static boolean isUnsafeMethod(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)
                || "DELETE".equals(method);
    }

    static String propertyOrDefault(AuraWebRuntime app, String key, String fallback) {
        String value = app.prop(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record HostGuard(AuraWebRuntime app) implements RequestGuard {
        @Override
        public GuardOutcome evaluate(GuardedRequest request) {
            if (hostAllowed(request.hostName())) return GuardOutcome.next();
            app.emit(AuraEvent.of("request.rejected.host", Map.of("method", request.method())));
            return GuardOutcome.reject(421, "Misdirected Request");
        }

        private boolean hostAllowed(String host) {
            if (host == null) return false;
            if (!app.allowedHosts().isEmpty()) return app.allowedHosts().contains(host.toLowerCase());
            if (app.publicOrigin() == null) return true;
            try {
                return host.equalsIgnoreCase(URI.create(app.publicOrigin()).getHost());
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
    }

    /** Concurrency, rate limiting, and any module-supplied admission policy. */
    private static final class AdmissionGuard implements RequestGuard {

        private final AuraWebRuntime app;
        private final RoutePattern routePattern;
        private final AtomicInteger inFlight = new AtomicInteger();
        private final FixedWindowRateLimiter rateLimiter;

        AdmissionGuard(AuraWebRuntime app, RoutePattern routePattern) {
            this.app = app;
            this.routePattern = routePattern;
            this.rateLimiter = new FixedWindowRateLimiter(app.rateLimitMaxKeys());
        }

        @Override
        public GuardOutcome evaluate(GuardedRequest request) {
            String method = request.method();
            String path = request.path();
            AdmissionPolicy custom = app.admissionPolicy();
            if (custom != null) return evaluateCustom(request, custom, method, path);

            int limit = app.concurrencyLimit();
            if (limit > 0 && inFlight.incrementAndGet() > limit) {
                app.emit(AuraEvent.of("request.rejected.concurrency", Map.of("limit", limit)));
                inFlight.decrementAndGet();
                return GuardOutcome.reject(503, "Service Unavailable");
            }
            int rate = app.rateLimitPerSecond();
            if (rate > 0) {
                String key = clientIp(request);
                long now = System.currentTimeMillis() / 1000;
                if (!rateLimiter.tryAcquire(key, rate, now)) {
                    app.emit(AuraEvent.of("request.rejected.rate",
                            Map.of("ratePerSecond", rate, "key", key)));
                    if (limit > 0) inFlight.decrementAndGet();
                    request.responseHeader("Retry-After", "1");
                    return GuardOutcome.reject(429, "Too Many Requests");
                }
            }
            if (limit > 0) request.onComplete(() -> inFlight.decrementAndGet());
            return GuardOutcome.next();
        }

        private GuardOutcome evaluateCustom(GuardedRequest request, AdmissionPolicy custom,
                                            String method, String path) {
            AdmissionDecision decision;
            try {
                decision = custom.admit(new AdmissionRequest(method, path,
                        routePattern.matching(method, path), clientIp(request),
                        request.header("X-API-Key")));
                if (decision == null) throw new IllegalStateException("Admission policy returned null");
            } catch (RuntimeException failure) {
                log.error("Admission policy failed closed for {} {}", method, path, failure);
                return rejectAdmission(request, 503, "AURA_ADMISSION_POLICY_ERROR", 0);
            }
            if (!decision.allowed()) {
                app.emit(AuraEvent.of("request.rejected.admission", Map.of(
                        "method", method, "path", path, "status", decision.status(),
                        "code", decision.code())));
                GuardOutcome outcome = rejectAdmission(request, decision.status(),
                        decision.code(), decision.retryAfterSeconds());
                decision.close();
                return outcome;
            }
            request.onComplete(decision::close);
            return GuardOutcome.next();
        }

        private GuardOutcome rejectAdmission(GuardedRequest request, int status,
                                             String code, int retryAfterSeconds) {
            if (retryAfterSeconds > 0) {
                request.responseHeader("Retry-After", Integer.toString(retryAfterSeconds));
            }
            return GuardOutcome.payload(status, JSON.toJSONString(Map.of(
                    "error", "Request admission rejected", "code", code)));
        }

        private String clientIp(GuardedRequest request) {
            String peer = request.peerIp();
            String key = Context.resolveClientIp(String.join(",", app.trustedProxies()), peer,
                    request.header("X-Forwarded-For"));
            return key == null || key.isBlank() ? "unknown" : key;
        }
    }

    private record SecurityHeadersGuard(AuraWebRuntime app) implements RequestGuard {
        @Override
        public GuardOutcome evaluate(GuardedRequest request) {
            if (app.securityHeaders()) {
                request.responseHeader("X-Content-Type-Options", "nosniff");
                request.responseHeader("X-Frame-Options", "DENY");
                request.responseHeader("Referrer-Policy", "no-referrer");
                if (request.secure()) {
                    request.responseHeader("Strict-Transport-Security",
                            "max-age=31536000; includeSubDomains");
                }
            }
            return GuardOutcome.next();
        }
    }

    private record CsrfGuard(AuraWebRuntime app) implements RequestGuard {
        @Override
        public GuardOutcome evaluate(GuardedRequest request) {
            String enabled = app.prop("aura.csrf.enabled");
            boolean enforce = enabled != null ? Boolean.parseBoolean(enabled)
                    : (app.publicOrigin() != null && !"dev".equalsIgnoreCase(app.env()));
            if (!enforce || !isUnsafeMethod(request.method())) return GuardOutcome.next();

            String headerName = propertyOrDefault(app, "aura.csrf.header", "X-CSRF-Token");
            String cookieName = propertyOrDefault(app, "aura.csrf.cookie", "csrf_token");
            String header = request.header(headerName);
            String cookie = request.cookie(cookieName);
            if (header != null && !header.isBlank() && cookie != null && !cookie.isBlank()
                    && MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8),
                    cookie.getBytes(StandardCharsets.UTF_8))) {
                return GuardOutcome.next();
            }

            String origin = request.header("Origin");
            if (origin == null) {
                String referer = request.header("Referer");
                if (referer != null) {
                    try {
                        URI parsed = URI.create(referer);
                        origin = parsed.getScheme() + "://" + parsed.getAuthority();
                    } catch (IllegalArgumentException ignored) {
                        return rejected();
                    }
                }
            }
            if (origin == null) return rejected();
            return expectedOrigin(request).equalsIgnoreCase(origin)
                    ? GuardOutcome.next()
                    : rejected();
        }

        private String expectedOrigin(GuardedRequest request) {
            String configured = app.publicOrigin();
            if (configured == null) configured = app.prop("aura.public-origin");
            if (configured != null && !configured.isBlank()) return configured.trim();
            String scheme = request.scheme();
            String host = request.hostAndPort();
            String peer = request.peerIp();
            if (Context.isTrustedProxy(String.join(",", app.trustedProxies()), peer)) {
                String forwardedProto = request.header("X-Forwarded-Proto");
                String forwardedHost = request.header("X-Forwarded-Host");
                if (forwardedProto != null && forwardedProto.matches("(?i)https?")) {
                    scheme = forwardedProto.toLowerCase();
                }
                if (forwardedHost != null
                        && forwardedHost.matches("[A-Za-z0-9.-]+(?::[0-9]{1,5})?")) {
                    host = forwardedHost;
                }
            }
            return scheme + "://" + host;
        }

        private static GuardOutcome rejected() {
            return GuardOutcome.payload(403, "{\"error\":\"CSRF validation failed\"}");
        }
    }

    private record CorsGuard(AuraWebRuntime app) implements RequestGuard {
        @Override
        public GuardOutcome evaluate(GuardedRequest request) {
            String corsOrigin = app.corsOrigin();
            if (corsOrigin == null) return GuardOutcome.next();

            CorsConfig corsConfig = app.corsConfig();
            String allowOrigin;
            String allowHeaders;
            boolean credentials = false;
            if (corsConfig != null) {
                String requestOrigin = request.header("Origin");
                allowOrigin = corsConfig.resolveOrigin(requestOrigin);
                allowHeaders = corsConfig.headers();
                credentials = corsConfig.credentials();
                if (corsConfig.hasWildcardOrigin() && credentials) {
                    // A wildcard cannot be combined with credentialed CORS. Refuse to reflect
                    // an arbitrary browser Origin rather than echoing the request back.
                    allowOrigin = null;
                    credentials = false;
                }
                if (requestOrigin != null && !"*".equals(allowOrigin)) {
                    request.addResponseHeader("Vary", "Origin");
                }
            } else {
                allowOrigin = corsOrigin;
                allowHeaders = "Content-Type, Authorization";
            }
            if (allowOrigin != null) {
                request.responseHeader("Access-Control-Allow-Origin", allowOrigin);
                request.responseHeader("Access-Control-Allow-Methods",
                        "GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS");
                request.responseHeader("Access-Control-Allow-Headers", allowHeaders);
                request.responseHeader("Access-Control-Max-Age", "86400");
                if (credentials) request.responseHeader("Access-Control-Allow-Credentials", "true");
            }
            if ("OPTIONS".equals(request.method())) return GuardOutcome.stop(204);
            return GuardOutcome.next();
        }
    }
}
