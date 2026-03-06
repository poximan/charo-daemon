package com.charodaemon.monitor.model;

import java.util.List;

public final class AggregatedMetricsSnapshot {
    private final String instanceId;
    private final String generatedAt;
    private final String latestSampleTimestamp;
    private final int samples;
    private final long windowSeconds;
    private final long timeoutSeconds;
    private final double cpuLoad;
    private final double cpuTemperatureCelsius;
    private final double memoryUsageRatio;
    private final long freeMemoryBytes;
    private final long totalMemoryBytes;
    private final List<NetworkInterfaceInfo> networkInterfaces;
    private final List<ProcessStatus> watchedProcesses;
    private final String temperatureSensorsReport;

    public AggregatedMetricsSnapshot(
            String instanceId,
            String generatedAt,
            String latestSampleTimestamp,
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
        this.instanceId = instanceId;
        this.generatedAt = generatedAt;
        this.latestSampleTimestamp = latestSampleTimestamp;
        this.samples = samples;
        this.windowSeconds = windowSeconds;
        this.timeoutSeconds = timeoutSeconds;
        this.cpuLoad = cpuLoad;
        this.cpuTemperatureCelsius = cpuTemperatureCelsius;
        this.memoryUsageRatio = memoryUsageRatio;
        this.freeMemoryBytes = freeMemoryBytes;
        this.totalMemoryBytes = totalMemoryBytes;
        this.networkInterfaces = networkInterfaces;
        this.watchedProcesses = watchedProcesses;
        this.temperatureSensorsReport = temperatureSensorsReport;
    }

    public String instanceId() {
        return instanceId;
    }

    public String generatedAt() {
        return generatedAt;
    }

    public String latestSampleTimestamp() {
        return latestSampleTimestamp;
    }

    public int samples() {
        return samples;
    }

    public long windowSeconds() {
        return windowSeconds;
    }

    public long timeoutSeconds() {
        return timeoutSeconds;
    }

    public double cpuLoad() {
        return cpuLoad;
    }

    public double cpuTemperatureCelsius() {
        return cpuTemperatureCelsius;
    }

    public double memoryUsageRatio() {
        return memoryUsageRatio;
    }

    public long freeMemoryBytes() {
        return freeMemoryBytes;
    }

    public long totalMemoryBytes() {
        return totalMemoryBytes;
    }

    public List<NetworkInterfaceInfo> networkInterfaces() {
        return networkInterfaces;
    }

    public List<ProcessStatus> watchedProcesses() {
        return watchedProcesses;
    }

    public String temperatureSensorsReport() {
        return temperatureSensorsReport;
    }
}
