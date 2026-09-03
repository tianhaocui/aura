package io.aura.snapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class SnapshotScope {

    public enum Mode {
        CAPTURE,
        REPLAY
    }

    private final Mode mode;
    private final ExceptionSnapshot replaySnapshot;
    private final Map<String, Object> artifacts = new LinkedHashMap<>();
    private final List<ExceptionSnapshot.ExternalCallSnapshot> externalCalls = new ArrayList<>();
    private ExceptionSnapshot.ResponseSnapshot response;
    private int replayExternalIndex;

    private SnapshotScope(Mode mode, ExceptionSnapshot replaySnapshot) {
        this.mode = mode;
        this.replaySnapshot = replaySnapshot;
        if (replaySnapshot != null && replaySnapshot.externalCalls() != null) {
            externalCalls.addAll(replaySnapshot.externalCalls());
        }
    }

    public static SnapshotScope capture() {
        return new SnapshotScope(Mode.CAPTURE, null);
    }

    public static SnapshotScope replay(ExceptionSnapshot snapshot) {
        return new SnapshotScope(Mode.REPLAY, snapshot);
    }

    public Mode mode() { return mode; }
    public boolean replaying() { return mode == Mode.REPLAY; }
    public Map<String, Object> artifacts() { return artifacts; }
    public List<ExceptionSnapshot.ExternalCallSnapshot> externalCalls() { return externalCalls; }
    public ExceptionSnapshot.ResponseSnapshot response() { return response; }
    public ExceptionSnapshot replaySnapshot() { return replaySnapshot; }

    public void put(String key, Object value) {
        if (key != null && !key.isBlank()) artifacts.put(key, value);
    }

    public void externalCall(ExceptionSnapshot.ExternalCallSnapshot call) {
        if (call != null && mode == Mode.CAPTURE) externalCalls.add(call);
    }

    public void response(int status, String contentType, Object body) {
        this.response = new ExceptionSnapshot.ResponseSnapshot(status, Map.of(), contentType, body);
    }

    public ExceptionSnapshot.ExternalCallSnapshot nextReplayExternalCall(String name, String method, String url) {
        if (!replaying()) return null;
        while (replayExternalIndex < externalCalls.size()) {
            ExceptionSnapshot.ExternalCallSnapshot call = externalCalls.get(replayExternalIndex++);
            boolean nameMatches = name == null || name.equals(call.name());
            boolean methodMatches = method == null || method.equalsIgnoreCase(call.method());
            boolean urlMatches = urlsMatch(url, call.url());
            if (nameMatches && methodMatches && urlMatches) return call;
        }
        throw new IllegalStateException("No replay external call matched: " + method + " " + url + " (" + name + ")");
    }

    private static boolean urlsMatch(String requested, String recorded) {
        if (requested == null || recorded == null) return requested == null && recorded == null;
        if (requested.equals(recorded)) return true;
        try {
            URI left = URI.create(requested);
            URI right = URI.create(recorded);
            boolean sameOrigin = java.util.Objects.equals(left.getScheme(), right.getScheme())
                    && java.util.Objects.equals(left.getHost(), right.getHost())
                    && effectivePort(left) == effectivePort(right);
            if (!sameOrigin) return false;
            String redactedPrefix = "/.aura-redacted/";
            if (right.getRawPath() != null && right.getRawPath().startsWith(redactedPrefix)) {
                String expectedHash = right.getRawPath().substring(redactedPrefix.length());
                String rawTarget = (left.getRawPath() == null ? "" : left.getRawPath())
                        + (left.getRawQuery() == null ? "" : "?" + left.getRawQuery());
                return expectedHash.equals(sha256(rawTarget));
            }
            return java.util.Objects.equals(left.getRawPath(), right.getRawPath())
                    && java.util.Objects.equals(left.getRawQuery(), right.getRawQuery());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
