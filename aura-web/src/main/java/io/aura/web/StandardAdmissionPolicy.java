package io.aura.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;

/** Bounded standard admission policy for IP, API-key, route, and concurrency rules. */
public final class StandardAdmissionPolicy implements AdmissionPolicy {
    public static final String RATE_LIMITED = "AURA_RATE_LIMITED";
    public static final String CONCURRENCY_LIMITED = "AURA_CONCURRENCY_LIMITED";

    private final List<RateRule> rateRules;
    private final List<BulkheadRule> bulkheadRules;
    private final AtomicLong rateRejections = new AtomicLong();
    private final AtomicLong concurrencyRejections = new AtomicLong();

    private StandardAdmissionPolicy(List<RateRule> rateRules, List<BulkheadRule> bulkheadRules) {
        this.rateRules = List.copyOf(rateRules);
        this.bulkheadRules = List.copyOf(bulkheadRules);
    }

    public static Builder builder() { return new Builder(); }

    @Override public AdmissionDecision admit(AdmissionRequest request) {
        Objects.requireNonNull(request, "request");
        long nowSecond = System.currentTimeMillis() / 1000;
        for (RateRule rule : rateRules) {
            if (!rule.matches.test(request)) continue;
            String key = rule.key.apply(request);
            if (!rule.limiter.tryAcquire(key == null || key.isBlank() ? "<anonymous>" : key,
                    rule.permitsPerSecond, nowSecond)) {
                rateRejections.incrementAndGet();
                return AdmissionDecision.reject(429, RATE_LIMITED, 1);
            }
        }

        List<BulkheadRule> acquired = new ArrayList<>();
        for (BulkheadRule rule : bulkheadRules) {
            if (!rule.matches.test(request)) continue;
            if (rule.inFlight.incrementAndGet() > rule.limit) {
                rule.inFlight.decrementAndGet();
                for (int i = acquired.size() - 1; i >= 0; i--) acquired.get(i).inFlight.decrementAndGet();
                concurrencyRejections.incrementAndGet();
                return AdmissionDecision.reject(503, CONCURRENCY_LIMITED, 0);
            }
            acquired.add(rule);
        }
        if (acquired.isEmpty()) return AdmissionDecision.allow();
        return AdmissionDecision.allow(() -> {
            for (int i = acquired.size() - 1; i >= 0; i--) acquired.get(i).inFlight.decrementAndGet();
        });
    }

    public long rateRejections() { return rateRejections.get(); }
    public long concurrencyRejections() { return concurrencyRejections.get(); }
    public int trackedRateKeys() {
        return rateRules.stream().mapToInt(rule -> rule.limiter.trackedKeyCount()).sum();
    }
    public int inFlight() {
        return bulkheadRules.stream().mapToInt(rule -> rule.inFlight.get()).sum();
    }

    public static final class Builder {
        private final List<RateRule> rateRules = new ArrayList<>();
        private final List<BulkheadRule> bulkheadRules = new ArrayList<>();

        public Builder perIp(int permitsPerSecond, int maxClientKeys) {
            return rate("per-ip", request -> true, AdmissionRequest::clientIp,
                    permitsPerSecond, maxClientKeys);
        }

        public Builder perApiKey(int permitsPerSecond, int maxApiKeys) {
            return rate("per-api-key", request -> true, AdmissionRequest::apiKey,
                    permitsPerSecond, maxApiKeys);
        }

        public Builder routeRate(String routePattern, int permitsPerSecond) {
            String pattern = requireRoute(routePattern);
            return rate("route:" + pattern, request -> pattern.equals(request.routePattern()),
                    ignored -> pattern, permitsPerSecond, 1);
        }

        public Builder globalConcurrency(int limit) {
            return bulkhead("global", request -> true, limit);
        }

        public Builder routeConcurrency(String routePattern, int limit) {
            String pattern = requireRoute(routePattern);
            return bulkhead("route:" + pattern,
                    request -> pattern.equals(request.routePattern()), limit);
        }

        public StandardAdmissionPolicy build() {
            if (rateRules.isEmpty() && bulkheadRules.isEmpty()) {
                throw new IllegalStateException("At least one admission rule is required");
            }
            return new StandardAdmissionPolicy(rateRules, bulkheadRules);
        }

        private Builder rate(String id, Predicate<AdmissionRequest> matches,
                             Function<AdmissionRequest, String> key,
                             int permitsPerSecond, int maxKeys) {
            if (permitsPerSecond < 1) throw new IllegalArgumentException("permitsPerSecond must be positive");
            rateRules.add(new RateRule(id, matches, key, permitsPerSecond,
                    new FixedWindowRateLimiter(maxKeys)));
            return this;
        }

        private Builder bulkhead(String id, Predicate<AdmissionRequest> matches, int limit) {
            if (limit < 1) throw new IllegalArgumentException("concurrency limit must be positive");
            bulkheadRules.add(new BulkheadRule(id, matches, limit));
            return this;
        }

        private static String requireRoute(String routePattern) {
            if (routePattern == null || routePattern.isBlank() || !routePattern.startsWith("/")) {
                throw new IllegalArgumentException("route pattern must start with '/'");
            }
            return routePattern.trim();
        }
    }

    private record RateRule(String id, Predicate<AdmissionRequest> matches,
                            Function<AdmissionRequest, String> key, int permitsPerSecond,
                            FixedWindowRateLimiter limiter) { }

    private static final class BulkheadRule {
        private final String id;
        private final Predicate<AdmissionRequest> matches;
        private final int limit;
        private final AtomicInteger inFlight = new AtomicInteger();

        private BulkheadRule(String id, Predicate<AdmissionRequest> matches, int limit) {
            this.id = id;
            this.matches = matches;
            this.limit = limit;
        }
    }
}
