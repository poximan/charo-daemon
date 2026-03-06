package com.charodaemon.monitor;

import com.charodaemon.monitor.config.MonitorSettings;
import com.charodaemon.monitor.model.NetworkAddressInfo;
import com.charodaemon.monitor.model.NetworkInterfaceInfo;
import com.charodaemon.monitor.model.ProcessStatus;
import com.charodaemon.monitor.model.TemperatureSensorReading;
import com.charodaemon.monitor.model.SystemMetrics;
import com.sun.management.OperatingSystemMXBean;
import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.Sensors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public final class SystemMonitor implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SystemMonitor.class);

    private static final Duration MIN_INTERVAL = Duration.ofSeconds(1);
    private static final double UNKNOWN_TEMPERATURE = -1.0;
    private static final double MAX_REASONABLE_CPU_TEMP_C = 150.0;
    private final OperatingSystemMXBean osBean;
    private final ScheduledExecutorService executor;
    private final AtomicReference<SystemMetrics> latestMetrics = new AtomicReference<>();
    private final Object schedulingLock = new Object();
    private final MonitorSettings settings;
    private final HardwareAbstractionLayer hardware;
    private volatile double lastKnownCpuTempC = UNKNOWN_TEMPERATURE;
    private volatile List<TemperatureSensorReading> lastKnownTemperatureSensors = Collections.emptyList();

    private volatile Duration samplingInterval;
    private final CopyOnWriteArrayList<String> processWatchList = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> interfaceExcludePatterns = new CopyOnWriteArrayList<>();
    private volatile ScheduledFuture<?> scheduledTask;
    private final AtomicBoolean firstSampleLogged = new AtomicBoolean(false);
    private record TemperatureQueryResult(double representativeValue, List<TemperatureSensorReading> readings, boolean fromCache) {}

    public SystemMonitor(MonitorSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        this.executor = Executors.newSingleThreadScheduledExecutor(new MonitorThreadFactory());
        HardwareAbstractionLayer detectedHardware = null;
        try {
            SystemInfo info = new SystemInfo();
            detectedHardware = info.getHardware();
        } catch (Throwable ignored) {
            detectedHardware = null;
        }
        this.hardware = detectedHardware;
        this.samplingInterval = normalizeInterval(settings.samplingInterval());
        if (!settings.initialProcessWatchList().isEmpty()) {
            this.processWatchList.addAll(settings.initialProcessWatchList());
        } else if (settings.processWatchListPath() != null) {
            loadProcessWatchListFromFile(settings.processWatchListPath());
        }
        if (!settings.initialNetworkInterfaceExcludeList().isEmpty()) {
            setInterfaceExcludePatterns(settings.initialNetworkInterfaceExcludeList());
        } else if (settings.networkInterfaceExcludePath() != null) {
            loadInterfaceExcludePatternsFromFile(settings.networkInterfaceExcludePath());
        }
    }

    public void start() {
        synchronized (schedulingLock) {
            if (scheduledTask != null && !scheduledTask.isCancelled()) {
                return;
            }
            scheduledTask = executor.scheduleAtFixedRate(
                    this::collectSafely,
                    0,
                    samplingInterval.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        }
    }

    private void collectSafely() {
        try {
            SystemMetrics metrics = collectMetrics();
            latestMetrics.set(metrics);
            if (firstSampleLogged.compareAndSet(false, true)) {
                LOG.info("[SystemMonitor] Primera muestra capturada en {} (cpuLoad={}, freeMemMB={})",
                        metrics.timestamp(),
                        metrics.cpuLoad(),
                        metrics.freeMemoryBytes() / (1024 * 1024));
            } else if (LOG.isDebugEnabled()) {
                LOG.debug("[SystemMonitor] Muestra recolectada ts={} cpuLoad={} freeMemMB={}",
                        metrics.timestamp(),
                        metrics.cpuLoad(),
                        metrics.freeMemoryBytes() / (1024 * 1024));
            }
        } catch (Exception ex) {
            LOG.warn("[SystemMonitor] Failed to collect metrics (interval={} ms)", samplingInterval.toMillis(), ex);
        }
    }

    public void stop() {
        synchronized (schedulingLock) {
            if (scheduledTask != null) {
                scheduledTask.cancel(false);
                scheduledTask = null;
            }
        }
    }

    public void updateSamplingInterval(Duration interval) {
        Duration normalized = normalizeInterval(interval);
        synchronized (schedulingLock) {
            this.samplingInterval = normalized;
            if (scheduledTask != null) {
                scheduledTask.cancel(false);
                scheduledTask = executor.scheduleAtFixedRate(
                        this::collectSafely,
                        0,
                        samplingInterval.toMillis(),
                        TimeUnit.MILLISECONDS
                );
            }
        }
    }

    private Duration normalizeInterval(Duration interval) {
        if (interval == null) {
            throw new IllegalArgumentException("samplingInterval cannot be null");
        }
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("samplingInterval must be positive");
        }
        if (interval.compareTo(MIN_INTERVAL) < 0) {
            return MIN_INTERVAL;
        }
        return interval;
    }

    public Duration currentSamplingInterval() {
        return samplingInterval;
    }

    public void setProcessWatchList(List<String> processes) {
        processWatchList.clear();
        if (processes != null) {
            processes.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(processWatchList::add);
        }
    }

    public List<String> currentProcessWatchList() {
        return new ArrayList<>(processWatchList);
    }

    public void setInterfaceExcludePatterns(List<String> patterns) {
        interfaceExcludePatterns.clear();
        if (patterns != null) {
            patterns.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(interfaceExcludePatterns::add);
        }
    }

    public List<String> currentInterfaceExcludePatterns() {
        return new ArrayList<>(interfaceExcludePatterns);
    }

    public void loadProcessWatchListFromFile(Path path) {
        if (path == null) {
            return;
        }
        try {
            List<String> processes = Files.readAllLines(path).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toList());
            setProcessWatchList(processes);
        } catch (IOException ex) {
            LOG.warn("[SystemMonitor] Unable to load process watch list from {}: {}", path, ex.toString());
        }
    }

    public void loadInterfaceExcludePatternsFromFile(Path path) {
        if (path == null) {
            return;
        }
        try {
            List<String> patterns = Files.readAllLines(path).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toList());
            setInterfaceExcludePatterns(patterns);
        } catch (IOException ex) {
            LOG.warn("[SystemMonitor] Unable to load interface exclude list from {}: {}", path, ex.toString());
        }
    }

    public SystemMetrics getLatestMetrics() {
        return latestMetrics.get();
    }

    private SystemMetrics collectMetrics() {
        double cpuLoadRaw = osBean.getCpuLoad();
        double cpuLoad = cpuLoadRaw >= 0.0 ? cpuLoadRaw : -1.0;

        long totalMemory = osBean.getTotalMemorySize();
        long freeMemory = osBean.getFreeMemorySize();

        List<NetworkInterfaceInfo> networkInterfaces = collectNetworkInterfaces();
        List<ProcessStatus> processStatuses = collectProcessStatuses();
        TemperatureQueryResult temperatureResult = readCpuTemperatureDetailed();
        double cpuTemperature = temperatureResult.representativeValue();
        List<TemperatureSensorReading> temperatureSensors = temperatureResult.readings();

        return new SystemMetrics(
                Instant.now(),
                cpuLoad,
                cpuTemperature,
                temperatureSensors,
                totalMemory,
                freeMemory,
                networkInterfaces,
                processStatuses
        );
    }

    private TemperatureQueryResult readCpuTemperatureDetailed() {
        List<TemperatureSensorReading> readings = isWindows() ? readWindowsTemperatureSensors() : readNonWindowsTemperatureSensors();
        if (!readings.isEmpty()) {
            double representative = selectRepresentativeTemperature(readings);
            if (Double.isFinite(representative) && representative > 0.0d) {
                lastKnownCpuTempC = representative;
            } else {
                LOG.warn("[SystemMonitor] Ninguna lectura valida en {}", readings);
            }
            lastKnownTemperatureSensors = readings;
            return new TemperatureQueryResult(representative, readings, false);
        }
        if (Double.isFinite(lastKnownCpuTempC) && lastKnownCpuTempC > 0.0d && !lastKnownTemperatureSensors.isEmpty()) {
            LOG.warn("[SystemMonitor] No se obtuvieron sensores de temperatura en esta muestra, devolviendo la ultima lectura valida");
            return new TemperatureQueryResult(lastKnownCpuTempC, lastKnownTemperatureSensors, true);
        }
        LOG.warn("[SystemMonitor] No se pudieron obtener sensores de temperatura");
        return new TemperatureQueryResult(UNKNOWN_TEMPERATURE, Collections.emptyList(), false);
    }
    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    private List<TemperatureSensorReading> readWindowsTemperatureSensors() {
        List<TemperatureSensorReading> readings = new ArrayList<>();
        ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-Command",
                "wmic /namespace:\\\\root\\wmi PATH MSAcpi_ThermalZoneTemperature get CurrentTemperature"
        );
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int sensorIndex = 0;
                while ((line = r.readLine()) != null) {
                    line = stripBom(line.trim());
                    if (line.isEmpty() || line.equalsIgnoreCase("CurrentTemperature")) {
                        continue;
                    }
                    readings.add(decodeKelvinReading("sensor-" + (++sensorIndex), line));
                }
            }
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                LOG.warn("[SystemMonitor] Consulta WMI de temperatura excedio 2s");
                p.destroyForcibly();
            }
        } catch (Exception ex) {
            LOG.warn("[SystemMonitor] Lectura WMI de temperatura fallo: {}", ex.toString());
        }
        return readings;
    }

    private List<TemperatureSensorReading> readNonWindowsTemperatureSensors() {
        List<TemperatureSensorReading> readings = new ArrayList<>();
        HardwareAbstractionLayer hal = this.hardware;
        if (hal == null) {
            return readings;
        }
        try {
            Sensors sensors = hal.getSensors();
            if (sensors != null) {
                double value = sensors.getCpuTemperature();
                boolean valid = Double.isFinite(value) && value > 0.0d && value < MAX_REASONABLE_CPU_TEMP_C;
                readings.add(new TemperatureSensorReading("oshi", value, valid));
            }
        } catch (Throwable ex) {
            LOG.warn("[SystemMonitor] Lectura OSHI de temperatura fallo: {}", ex.toString());
        }
        return readings;
    }

    private TemperatureSensorReading decodeKelvinReading(String source, String rawValue) {
        try {
            double raw = Double.parseDouble(rawValue);
            double celsius = (raw / 10.0) - 273.15;
            boolean valid = Double.isFinite(celsius) && celsius > 0.0d && celsius < MAX_REASONABLE_CPU_TEMP_C;
            return new TemperatureSensorReading(source, celsius, valid);
        } catch (NumberFormatException ex) {
            LOG.warn("[SystemMonitor] Valor de temperatura no numerico ({}) para {}", rawValue, source);
            return new TemperatureSensorReading(source, UNKNOWN_TEMPERATURE, false);
        }
    }

    private double selectRepresentativeTemperature(List<TemperatureSensorReading> readings) {
        double best = UNKNOWN_TEMPERATURE;
        for (TemperatureSensorReading reading : readings) {
            if (!reading.valid()) {
                continue;
            }
            double value = reading.celsius();
            if (!Double.isFinite(value)) {
                continue;
            }
            if (value > best) {
                best = value;
            }
        }
        return best;
    }
    private static String stripBom(String value) {
        if (value != null && !value.isEmpty() && value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
    }

    private List<NetworkInterfaceInfo> collectNetworkInterfaces() {
        List<NetworkInterfaceInfo> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return Collections.emptyList();
            }

            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (shouldExcludeInterface(networkInterface)) {
                    continue;
                }
                String interfacePath = determineInterfacePath(networkInterface);
                List<NetworkAddressInfo> addresses = new ArrayList<>();
                for (InterfaceAddress address : networkInterface.getInterfaceAddresses()) {
                    if (address.getAddress() == null) {
                        continue;
                    }
                    String hostAddress = address.getAddress().getHostAddress();
                    String netmask = determineNetmask(address);
                    addresses.add(new NetworkAddressInfo(hostAddress, netmask));
                }

                String mac = formatMacAddress(networkInterface);
                result.add(new NetworkInterfaceInfo(
                        networkInterface.getName(),
                        networkInterface.getDisplayName(),
                        interfacePath,
                        mac,
                        safeIsUp(networkInterface),
                        networkInterface.isVirtual(),
                        addresses
                ));
            }
        } catch (SocketException e) {
            System.err.println("[SystemMonitor] Unable to enumerate network interfaces: " + e.getMessage());
        }
        return result;
    }

    private boolean shouldExcludeInterface(NetworkInterface networkInterface) {
        if (interfaceExcludePatterns.isEmpty()) {
            return false;
        }
        String displayName = networkInterface.getDisplayName();
        String path = determineInterfacePath(networkInterface);
        StringBuilder builder = new StringBuilder();
        if (displayName != null) {
            builder.append(displayName);
        }
        if (path != null && !path.isEmpty()) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(path);
        }
        String normalized = builder.toString().toLowerCase(Locale.ROOT);
        for (String pattern : interfaceExcludePatterns) {
            String lowered = pattern.toLowerCase(Locale.ROOT);
            if (!lowered.isEmpty() && normalized.contains(lowered)) {
                return true;
            }
        }
        return false;
    }

    private boolean safeIsUp(NetworkInterface networkInterface) {
        try {
            return networkInterface.isUp();
        } catch (SocketException e) {
            return false;
        }
    }

    private String determineNetmask(InterfaceAddress address) {
        short prefixLength = address.getNetworkPrefixLength();
        if (prefixLength < 0 || prefixLength > 32) {
            return "unknown";
        }

        int mask = 0xffffffff << (32 - prefixLength);
        int value = mask;
        byte[] bytes = new byte[]{
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        };
        return String.format(Locale.ROOT, "%d.%d.%d.%d",
                bytes[0] & 0xff,
                bytes[1] & 0xff,
                bytes[2] & 0xff,
                bytes[3] & 0xff
        );
    }

    private String formatMacAddress(NetworkInterface networkInterface) {
        try {
            byte[] hardwareAddress = networkInterface.getHardwareAddress();
            if (hardwareAddress == null || hardwareAddress.length == 0) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < hardwareAddress.length; i++) {
                builder.append(String.format(Locale.ROOT, "%02X", hardwareAddress[i]));
                if (i < hardwareAddress.length - 1) {
                    builder.append(":");
                }
            }
            return builder.toString();
        } catch (SocketException e) {
            return "";
        }
    }

    private String determineInterfacePath(NetworkInterface networkInterface) {
        List<String> segments = new ArrayList<>();
        NetworkInterface current = networkInterface;
        while (current != null) {
            segments.add(current.getName());
            current = current.getParent();
        }
        Collections.reverse(segments);
        return String.join("/", segments);
    }

    private List<ProcessStatus> collectProcessStatuses() {
        if (processWatchList.isEmpty()) {
            return Collections.emptyList();
        }

        var normalizedNames = processWatchList.stream()
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toMap(
                        name -> name.toLowerCase(Locale.ROOT),
                        name -> name,
                        (existing, ignored) -> existing,
                        java.util.LinkedHashMap::new
                ));

        var matching = new java.util.LinkedHashMap<String, java.util.Set<Long>>();
        try {
            ProcessHandle.allProcesses().forEach(handle -> {
                var commandOpt = handle.info().command();
                if (commandOpt.isEmpty()) {
                    return;
                }
                String key = processKey(commandOpt.get());
                matching.computeIfAbsent(key, k -> new java.util.LinkedHashSet<>()).add(handle.pid());
            });
        } catch (Exception ex) {
            LOG.warn("[SystemMonitor] No se pudo enumerar procesos para watch list", ex);
        }

        List<ProcessStatus> statuses = new ArrayList<>(normalizedNames.size());
        for (var entry : normalizedNames.entrySet()) {
            Set<Long> pids = matching.getOrDefault(entry.getKey(), Collections.emptySet());
            statuses.add(new ProcessStatus(
                    entry.getValue(),
                    !pids.isEmpty(),
                    new ArrayList<>(pids)
            ));
        }
        return statuses;
    }

    private String processKey(String commandRaw) {
        String normalized = commandRaw.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
        return fileName.toLowerCase(Locale.ROOT);
    }

    @Override
    public void close() {
        stop();
        executor.shutdown();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class MonitorThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "system-monitor");
            thread.setDaemon(true);
            return thread;
        }
    }
}




















