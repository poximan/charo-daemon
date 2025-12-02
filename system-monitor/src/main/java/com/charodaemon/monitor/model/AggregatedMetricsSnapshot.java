package com.charodaemon.monitor.model;

import java.time.Instant;
import java.util.List;

public record AggregatedMetricsSnapshot(
        String instanceId,
        Instant generatedAt,
        Instant latestSampleTimestamp,
        int samples,
        long windowSeconds,
        long timeoutSeconds,
        double cpuLoadInstant,
        double averageCpuLoad,
        double cpuTemperatureInstant,
        double averageCpuTemperatureCelsius,
        double memoryUsageInstant,
        double averageMemoryUsageRatio,
        long freeMemoryBytesInstant,
        long totalMemoryBytesInstant,
        long averageFreeMemoryBytes,
        long averageTotalMemoryBytes,
        List<NetworkInterfaceInfo> networkInterfaces,
        List<ProcessStatus> watchedProcesses,
        SystemMetrics latestSample
) {
}
