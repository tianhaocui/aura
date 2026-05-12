package io.aura.web.monitor;

import java.util.List;

public record Metrics(
        long timestamp,
        Cpu cpu,
        Memory memory,
        List<Disk> disks,
        Load load
) {
    public record Cpu(double usagePercent, int cores) {}
    public record Memory(long totalMb, long usedMb, double usagePercent) {}
    public record Disk(String mount, long totalGb, long usedGb, double usagePercent) {}
    public record Load(double avg1, double avg5, double avg15) {}
}
