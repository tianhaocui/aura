package io.aura.web.monitor;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class MetricsCollector {

    private long prevCpuIdle = -1;
    private long prevCpuTotal = -1;

    public Metrics collect() {
        return new Metrics(
                Instant.now().toEpochMilli(),
                collectCpu(),
                collectMemory(),
                collectDisk(),
                collectLoad()
        );
    }

    private Metrics.Cpu collectCpu() {
        try {
            String stat = Files.readString(Path.of("/proc/stat"));
            String firstLine = stat.lines().findFirst().orElse("");
            String[] parts = firstLine.split("\\s+");
            if (parts.length < 8) return fallbackCpu();

            long user = Long.parseLong(parts[1]);
            long nice = Long.parseLong(parts[2]);
            long system = Long.parseLong(parts[3]);
            long idle = Long.parseLong(parts[4]);
            long iowait = Long.parseLong(parts[5]);
            long irq = Long.parseLong(parts[6]);
            long softirq = Long.parseLong(parts[7]);

            long total = user + nice + system + idle + iowait + irq + softirq;
            long idleTime = idle + iowait;

            double usage;
            if (prevCpuTotal < 0) {
                usage = (double) (total - idleTime) / total * 100;
            } else {
                long deltaTotal = total - prevCpuTotal;
                long deltaIdle = idleTime - prevCpuIdle;
                usage = deltaTotal == 0 ? 0 : (double) (deltaTotal - deltaIdle) / deltaTotal * 100;
            }
            prevCpuTotal = total;
            prevCpuIdle = idleTime;

            int cores = Runtime.getRuntime().availableProcessors();
            return new Metrics.Cpu(round(usage), cores);
        } catch (IOException e) {
            return fallbackCpu();
        }
    }

    private Metrics.Cpu fallbackCpu() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        double load = os.getSystemLoadAverage();
        int cores = Runtime.getRuntime().availableProcessors();
        double usage = load / cores * 100;
        return new Metrics.Cpu(round(Math.min(usage, 100)), cores);
    }

    private Metrics.Memory collectMemory() {
        try {
            String meminfo = Files.readString(Path.of("/proc/meminfo"));
            long totalKb = 0, availableKb = 0;
            for (String line : meminfo.lines().toList()) {
                if (line.startsWith("MemTotal:")) {
                    totalKb = parseMemInfoValue(line);
                } else if (line.startsWith("MemAvailable:")) {
                    availableKb = parseMemInfoValue(line);
                }
            }
            long totalMb = totalKb / 1024;
            long usedMb = (totalKb - availableKb) / 1024;
            double usage = totalKb == 0 ? 0 : (double) (totalKb - availableKb) / totalKb * 100;
            return new Metrics.Memory(totalMb, usedMb, round(usage));
        } catch (IOException e) {
            MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
            long max = mem.getHeapMemoryUsage().getMax() / 1024 / 1024;
            long used = mem.getHeapMemoryUsage().getUsed() / 1024 / 1024;
            return new Metrics.Memory(max, used, round((double) used / max * 100));
        }
    }

    private long parseMemInfoValue(String line) {
        String[] parts = line.split("\\s+");
        return parts.length >= 2 ? Long.parseLong(parts[1]) : 0;
    }

    private List<Metrics.Disk> collectDisk() {
        List<Metrics.Disk> disks = new ArrayList<>();
        for (FileStore store : FileSystems.getDefault().getFileStores()) {
            try {
                String name = store.toString();
                if (name.contains("/snap/") || name.contains("/proc/")
                        || name.contains("docker/overlay") || name.contains(".fake_dir")
                        || name.contains("tmpfs") || name.contains("udev")) continue;
                long total = store.getTotalSpace();
                long usable = store.getUsableSpace();
                if (total == 0) continue;
                long used = total - usable;
                double usage = (double) used / total * 100;
                disks.add(new Metrics.Disk(
                        name,
                        total / 1024 / 1024 / 1024,
                        used / 1024 / 1024 / 1024,
                        round(usage)
                ));
            } catch (IOException ignored) {}
        }
        return disks;
    }

    private Metrics.Load collectLoad() {
        try {
            String loadavg = Files.readString(Path.of("/proc/loadavg")).trim();
            String[] parts = loadavg.split("\\s+");
            return new Metrics.Load(
                    Double.parseDouble(parts[0]),
                    Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2])
            );
        } catch (IOException | NumberFormatException e) {
            double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
            return new Metrics.Load(load, load, load);
        }
    }

    private double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
