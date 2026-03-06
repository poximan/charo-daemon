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
        double cpuLoad,
        double cpuTemperatureCelsius,
        double memoryUsageRatio,
        long freeMemoryBytes,
        long totalMemoryBytes,
        List<NetworkInterfaceInfo> networkInterfaces,
        List<ProcessStatus> watchedProcesses,
        String temperatureSensorsReport
) {
}
