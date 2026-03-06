package com.charodaemon.monitor.model;

import java.util.List;

public final class SystemMetrics {
    private final String timestamp;
    private final double cpuLoad;
    private final double cpuTemperatureCelsius;
    private final List<TemperatureSensorReading> temperatureSensors;
    private final long totalMemoryBytes;
    private final long freeMemoryBytes;
    private final List<NetworkInterfaceInfo> networkInterfaces;
    private final List<ProcessStatus> watchedProcesses;

    public SystemMetrics(
            String timestamp,
            double cpuLoad,
            double cpuTemperatureCelsius,
            List<TemperatureSensorReading> temperatureSensors,
            long totalMemoryBytes,
            long freeMemoryBytes,
            List<NetworkInterfaceInfo> networkInterfaces,
            List<ProcessStatus> watchedProcesses
    ) {
        this.timestamp = timestamp;
        this.cpuLoad = cpuLoad;
        this.cpuTemperatureCelsius = cpuTemperatureCelsius;
        this.temperatureSensors = temperatureSensors;
        this.totalMemoryBytes = totalMemoryBytes;
        this.freeMemoryBytes = freeMemoryBytes;
        this.networkInterfaces = networkInterfaces;
        this.watchedProcesses = watchedProcesses;
    }

    public String timestamp() {
        return timestamp;
    }

    public double cpuLoad() {
        return cpuLoad;
    }

    public double cpuTemperatureCelsius() {
        return cpuTemperatureCelsius;
    }

    public List<TemperatureSensorReading> temperatureSensors() {
        return temperatureSensors;
    }

    public long totalMemoryBytes() {
        return totalMemoryBytes;
    }

    public long freeMemoryBytes() {
        return freeMemoryBytes;
    }

    public List<NetworkInterfaceInfo> networkInterfaces() {
        return networkInterfaces;
    }

    public List<ProcessStatus> watchedProcesses() {
        return watchedProcesses;
    }

    public double usedMemoryRatio() {
        long used = totalMemoryBytes - freeMemoryBytes;
        if (totalMemoryBytes <= 0) {
            return 0D;
        }
        return (double) used / (double) totalMemoryBytes;
    }
}
