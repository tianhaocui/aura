package io.aura.snapshot;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

public class SnapshotOptions {

    public enum BodyMode {
        NONE,
        CACHED_ONLY
    }

    private boolean enabled;
    private Path dir = Path.of("target", "aura-replays");
    private boolean async = true;
    private int queueSize = 1024;
    private BodyMode bodyMode = BodyMode.CACHED_ONLY;
    private int maxBodySize = 64 * 1024;
    private int maxExternalBodySize = 64 * 1024;
    private int maxStackTraceSize = 32 * 1024;
    private int maxSnapshotFileSize = 1024 * 1024;
    private long maxStorageSize = 64L * 1024 * 1024;
    private double sampleRate = 1.0;
    private final Set<String> redactHeaders = new LinkedHashSet<>(Set.of(
            "authorization", "cookie", "set-cookie", "x-api-key"));
    private final Set<String> redactFields = new LinkedHashSet<>(Set.of(
            "password", "token", "secret", "accessToken", "refreshToken", "paymentToken"));
    private final Set<String> envAllowlist = new LinkedHashSet<>(Set.of("AURA_ENV", "AURA_PORT"));

    public boolean enabled() { return enabled; }
    public Path dir() { return dir; }
    public boolean async() { return async; }
    public int queueSize() { return queueSize; }
    public BodyMode bodyMode() { return bodyMode; }
    public int maxBodySize() { return maxBodySize; }
    public int maxExternalBodySize() { return maxExternalBodySize; }
    public int maxStackTraceSize() { return maxStackTraceSize; }
    public int maxSnapshotFileSize() { return maxSnapshotFileSize; }
    public long maxStorageSize() { return maxStorageSize; }
    public double sampleRate() { return sampleRate; }
    public Set<String> redactHeaders() { return Set.copyOf(redactHeaders); }
    public Set<String> redactFields() { return Set.copyOf(redactFields); }
    public Set<String> envAllowlist() { return Set.copyOf(envAllowlist); }

    public SnapshotOptions enabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public SnapshotOptions dir(String dir) {
        this.dir = Path.of(dir);
        return this;
    }

    public SnapshotOptions dir(Path dir) {
        this.dir = dir;
        return this;
    }

    public SnapshotOptions async(boolean async) {
        this.async = async;
        return this;
    }

    public SnapshotOptions queueSize(int queueSize) {
        this.queueSize = Math.max(1, queueSize);
        return this;
    }

    public SnapshotOptions bodyMode(BodyMode bodyMode) {
        this.bodyMode = bodyMode != null ? bodyMode : BodyMode.CACHED_ONLY;
        return this;
    }

    public SnapshotOptions maxBodySize(int maxBodySize) {
        this.maxBodySize = Math.max(0, maxBodySize);
        return this;
    }

    public SnapshotOptions maxExternalBodySize(int maxExternalBodySize) {
        this.maxExternalBodySize = Math.max(0, maxExternalBodySize);
        return this;
    }

    public SnapshotOptions maxStackTraceSize(int maxStackTraceSize) {
        this.maxStackTraceSize = Math.max(0, maxStackTraceSize);
        return this;
    }

    public SnapshotOptions maxSnapshotFileSize(int maxSnapshotFileSize) {
        this.maxSnapshotFileSize = Math.max(0, maxSnapshotFileSize);
        return this;
    }

    public SnapshotOptions maxStorageSize(long maxStorageSize) {
        this.maxStorageSize = Math.max(0L, maxStorageSize);
        return this;
    }

    public SnapshotOptions sampleRate(double sampleRate) {
        this.sampleRate = Math.max(0.0, Math.min(1.0, sampleRate));
        return this;
    }

    public SnapshotOptions redactHeader(String header) {
        if (header != null && !header.isBlank()) redactHeaders.add(header.toLowerCase());
        return this;
    }

    public SnapshotOptions redactField(String field) {
        if (field != null && !field.isBlank()) redactFields.add(field);
        return this;
    }

    public SnapshotOptions envAllowlist(String... names) {
        envAllowlist.clear();
        if (names != null) {
            for (String name : names) {
                if (name != null && !name.isBlank()) envAllowlist.add(name);
            }
        }
        return this;
    }
}
