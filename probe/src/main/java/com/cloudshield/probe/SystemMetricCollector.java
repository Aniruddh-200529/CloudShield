package com.cloudshield.probe;

import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.springframework.stereotype.Component;

@Component
public class SystemMetricCollector {
    private static final Logger LOG = Logger.getLogger(SystemMetricCollector.class.getName());
    private final OperatingSystemMXBean os = java.lang.management.ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);

    public List<MetricObservation> collect() {
        Instant at = Instant.now();
        List<MetricObservation> result = new ArrayList<>();
        if (os != null) {
            try {
                double cpu = os.getCpuLoad();
                if (Double.isFinite(cpu) && cpu >= 0) add(result, "cpu.utilization", cpu * 100d, "%", at);
            } catch (UnsupportedOperationException | SecurityException ex) { LOG.fine("CPU metrics are unavailable on this host"); }
            try {
                long total = os.getTotalMemorySize(), free = os.getFreeMemorySize();
                if (total > 0 && free >= 0 && free <= total) {
                    add(result, "memory.utilization", ((double) (total - free) / total) * 100d, "%", at);
                    add(result, "memory.available", free, "bytes", at);
                }
            } catch (UnsupportedOperationException | SecurityException ex) { LOG.fine("Memory metrics are unavailable on this host"); }
        }
        try {
            Path path = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
            FileStore store = Files.getFileStore(path);
            long total = store.getTotalSpace(), available = store.getUsableSpace();
            if (total > 0 && available >= 0 && available <= total) {
                add(result, "disk.utilization", ((double) (total - available) / total) * 100d, "%", at);
                add(result, "disk.available", available, "bytes", at);
            }
        } catch (IOException | SecurityException ex) {
            LOG.fine("Disk metrics are unavailable on this host");
        }
        readLinuxNetworkCounters(result, at);
        return List.copyOf(result);
    }

    private void readLinuxNetworkCounters(List<MetricObservation> result, Instant at) {
        Path proc = Path.of("/proc/net/dev");
        if (!Files.isReadable(proc)) return;
        try {
            for (String line : Files.readAllLines(proc)) {
                String[] pair = line.trim().split(":", 2);
                if (pair.length != 2 || pair[0].equals("lo")) continue;
                String[] counters = pair[1].trim().split("\\s+");
                if (counters.length >= 9) {
                    add(result, "network.rx.bytes", Double.parseDouble(counters[0]), "bytes", at);
                    add(result, "network.tx.bytes", Double.parseDouble(counters[8]), "bytes", at);
                    return;
                }
            }
        } catch (IOException | NumberFormatException | SecurityException ex) {
            LOG.fine("Network counters are unavailable on this host");
        }
    }

    private static void add(List<MetricObservation> result, String type, double value, String unit, Instant at) {
        if (Double.isFinite(value) && value >= 0) result.add(new MetricObservation(UUID.randomUUID(), type, value, unit, at));
    }
}
