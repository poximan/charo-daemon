package com.charodaemon.monitor.model;

import java.time.Instant;
import java.util.List;

public record SystemMetrics(
        Instant timestamp,
        double cpuLoad,
        double cpuTemperatureCelsius,
        List<TemperatureSensorReading> temperatureSensors,
        long totalMemoryBytes,
        long freeMemoryBytes,
        List<NetworkInterfaceInfo> networkInterfaces,
        List<ProcessStatus> watchedProcesses
) {
    public double usedMemoryRatio() {
        long used = totalMemoryBytes - freeMemoryBytes;
        return totalMemoryBytes == 0 ? 0D : (double) used / totalMemoryBytes;
    }
}



