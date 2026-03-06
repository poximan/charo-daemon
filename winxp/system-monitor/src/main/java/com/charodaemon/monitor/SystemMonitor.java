package com.charodaemon.monitor;

import com.charodaemon.monitor.config.MonitorSettings;
import com.charodaemon.monitor.model.NetworkAddressInfo;
import com.charodaemon.monitor.model.NetworkInterfaceInfo;
import com.charodaemon.monitor.model.ProcessStatus;
import com.charodaemon.monitor.model.SystemMetrics;
import com.charodaemon.monitor.model.TemperatureSensorReading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class SystemMonitor {
    private static final Logger LOG = LoggerFactory.getLogger(SystemMonitor.class);
    private static final long MIN_INTERVAL_SECONDS = 1L;
    private static final double UNKNOWN_NUMERIC = -1.0d;

    private final ScheduledExecutorService executor;
    private final AtomicReference<SystemMetrics> latestMetrics = new AtomicReference<SystemMetrics>();
    private final Object schedulingLock = new Object();
    private final CopyOnWriteArrayList<String> processWatchList = new CopyOnWriteArrayList<String>();
    private final CopyOnWriteArrayList<String> interfaceExcludePatterns = new CopyOnWriteArrayList<String>();

    private volatile long samplingIntervalSeconds;
    private volatile ScheduledFuture<?> scheduledTask;

    public SystemMonitor(MonitorSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("settings es obligatorio");
        }
        this.executor = Executors.newSingleThreadScheduledExecutor(new MonitorThreadFactory());
        this.samplingIntervalSeconds = normalizeIntervalSeconds(settings.samplingIntervalSeconds());

        if (!settings.initialProcessWatchList().isEmpty()) {
            setProcessWatchList(settings.initialProcessWatchList());
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
            scheduledTask = executor.scheduleAtFixedRate(new Runnable() {
                public void run() {
                    collectSafely();
                }
            }, 0L, samplingIntervalSeconds, TimeUnit.SECONDS);
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

    public void updateSamplingIntervalSeconds(long seconds) {
        long normalized = normalizeIntervalSeconds(seconds);
        synchronized (schedulingLock) {
            this.samplingIntervalSeconds = normalized;
            if (scheduledTask != null) {
                scheduledTask.cancel(false);
                scheduledTask = executor.scheduleAtFixedRate(new Runnable() {
                    public void run() {
                        collectSafely();
                    }
                }, 0L, samplingIntervalSeconds, TimeUnit.SECONDS);
            }
        }
    }

    public long currentSamplingIntervalSeconds() {
        return samplingIntervalSeconds;
    }

    public void setProcessWatchList(List<String> processes) {
        processWatchList.clear();
        if (processes == null) {
            return;
        }
        for (int i = 0; i < processes.size(); i++) {
            String p = processes.get(i);
            if (p == null) {
                continue;
            }
            String trimmed = p.trim();
            if (trimmed.length() == 0) {
                continue;
            }
            processWatchList.add(trimmed);
        }
    }

    public List<String> currentProcessWatchList() {
        return new ArrayList<String>(processWatchList);
    }

    public void setInterfaceExcludePatterns(List<String> patterns) {
        interfaceExcludePatterns.clear();
        if (patterns == null) {
            return;
        }
        for (int i = 0; i < patterns.size(); i++) {
            String p = patterns.get(i);
            if (p == null) {
                continue;
            }
            String trimmed = p.trim();
            if (trimmed.length() == 0) {
                continue;
            }
            interfaceExcludePatterns.add(trimmed);
        }
    }

    public List<String> currentInterfaceExcludePatterns() {
        return new ArrayList<String>(interfaceExcludePatterns);
    }

    public void loadProcessWatchListFromFile(File file) {
        List<String> lines = readPlainListFile(file);
        setProcessWatchList(lines);
    }

    public void loadInterfaceExcludePatternsFromFile(File file) {
        List<String> lines = readPlainListFile(file);
        setInterfaceExcludePatterns(lines);
    }

    public SystemMetrics getLatestMetrics() {
        return latestMetrics.get();
    }

    private long normalizeIntervalSeconds(long seconds) {
        if (seconds < MIN_INTERVAL_SECONDS) {
            return MIN_INTERVAL_SECONDS;
        }
        return seconds;
    }

    private void collectSafely() {
        try {
            SystemMetrics metrics = collectMetrics();
            latestMetrics.set(metrics);
        } catch (Exception ex) {
            LOG.error("[SystemMonitor] Error recolectando metricas", ex);
        }
    }

    private SystemMetrics collectMetrics() {
        double cpuLoad = queryCpuLoadRatio();
        MemoryInfo memoryInfo = queryMemory();
        List<NetworkInterfaceInfo> networkInterfaces = collectNetworkInterfaces();
        List<ProcessStatus> statuses = collectProcessStatuses();

        List<TemperatureSensorReading> sensors = new ArrayList<TemperatureSensorReading>();
        sensors.add(new TemperatureSensorReading("cpu", UNKNOWN_NUMERIC, false));

        return new SystemMetrics(
                utcNowIso(),
                cpuLoad,
                UNKNOWN_NUMERIC,
                sensors,
                memoryInfo.totalBytes,
                memoryInfo.freeBytes,
                networkInterfaces,
                statuses
        );
    }

    private double queryCpuLoadRatio() {
        String[] cmd = new String[] {"wmic", "cpu", "get", "LoadPercentage", "/value"};
        List<String> output = executeCommand(cmd, 2000L);
        for (int i = 0; i < output.size(); i++) {
            String line = output.get(i);
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.length() == 0) {
                continue;
            }
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("loadpercentage=")) {
                String value = trimmed.substring("loadpercentage=".length()).trim();
                try {
                    double pct = Double.parseDouble(value);
                    if (pct < 0.0d) {
                        return UNKNOWN_NUMERIC;
                    }
                    return pct / 100.0d;
                } catch (NumberFormatException ex) {
                    LOG.warn("[SystemMonitor] CPU load no numerico: {}", value);
                    return UNKNOWN_NUMERIC;
                }
            }
        }
        LOG.warn("[SystemMonitor] CPU load no disponible via WMIC");
        return UNKNOWN_NUMERIC;
    }

    private MemoryInfo queryMemory() {
        String[] cmd = new String[] {"wmic", "OS", "get", "FreePhysicalMemory,TotalVisibleMemorySize", "/value"};
        List<String> output = executeCommand(cmd, 2000L);
        long freeKiB = -1L;
        long totalKiB = -1L;

        for (int i = 0; i < output.size(); i++) {
            String line = output.get(i);
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.length() == 0) {
                continue;
            }
            int idx = trimmed.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String key = trimmed.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            String value = trimmed.substring(idx + 1).trim();
            try {
                long parsed = Long.parseLong(value);
                if ("freephysicalmemory".equals(key)) {
                    freeKiB = parsed;
                } else if ("totalvisiblememorysize".equals(key)) {
                    totalKiB = parsed;
                }
            } catch (NumberFormatException ex) {
                LOG.warn("[SystemMonitor] Memoria no numerica para {}: {}", key, value);
            }
        }

        if (freeKiB < 0L || totalKiB < 0L) {
            LOG.warn("[SystemMonitor] Memoria no disponible via WMIC");
            return new MemoryInfo(0L, 0L);
        }

        long freeBytes = safeMultiply(freeKiB, 1024L);
        long totalBytes = safeMultiply(totalKiB, 1024L);
        return new MemoryInfo(totalBytes, freeBytes);
    }

    private List<NetworkInterfaceInfo> collectNetworkInterfaces() {
        List<NetworkInterfaceInfo> result = new ArrayList<NetworkInterfaceInfo>();
        Enumeration<NetworkInterface> interfaces;
        try {
            interfaces = NetworkInterface.getNetworkInterfaces();
        } catch (SocketException ex) {
            LOG.warn("[SystemMonitor] No se pudieron listar interfaces", ex);
            return result;
        }

        if (interfaces == null) {
            return result;
        }

        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            if (shouldExcludeInterface(ni)) {
                continue;
            }

            List<NetworkAddressInfo> addresses = new ArrayList<NetworkAddressInfo>();
            List<InterfaceAddress> ifaceAddresses = ni.getInterfaceAddresses();
            if (ifaceAddresses != null) {
                for (int i = 0; i < ifaceAddresses.size(); i++) {
                    InterfaceAddress ia = ifaceAddresses.get(i);
                    if (ia == null || ia.getAddress() == null) {
                        continue;
                    }
                    String ip = ia.getAddress().getHostAddress();
                    String netmask = determineNetmask(ia);
                    addresses.add(new NetworkAddressInfo(ip, netmask));
                }
            }

            result.add(new NetworkInterfaceInfo(
                    ni.getName(),
                    ni.getDisplayName(),
                    determineInterfacePath(ni),
                    formatMacAddress(ni),
                    safeIsUp(ni),
                    ni.isVirtual(),
                    addresses
            ));
        }

        return result;
    }

    private boolean shouldExcludeInterface(NetworkInterface ni) {
        if (interfaceExcludePatterns.isEmpty()) {
            return false;
        }
        String displayName = ni.getDisplayName() == null ? "" : ni.getDisplayName();
        String path = determineInterfacePath(ni);
        String full = (displayName + " " + path).toLowerCase(Locale.ROOT);

        for (int i = 0; i < interfaceExcludePatterns.size(); i++) {
            String pattern = interfaceExcludePatterns.get(i);
            if (pattern == null) {
                continue;
            }
            String p = pattern.toLowerCase(Locale.ROOT).trim();
            if (p.length() == 0) {
                continue;
            }
            if (full.indexOf(p) >= 0) {
                return true;
            }
        }
        return false;
    }

    private boolean safeIsUp(NetworkInterface ni) {
        try {
            return ni.isUp();
        } catch (SocketException ex) {
            return false;
        }
    }

    private String determineNetmask(InterfaceAddress address) {
        if (address == null || address.getAddress() == null) {
            return "unknown";
        }
        String ip = address.getAddress().getHostAddress();
        if (ip == null || ip.indexOf(':') >= 0) {
            return "unknown";
        }

        short prefixLength = address.getNetworkPrefixLength();
        if (prefixLength < 0 || prefixLength > 32) {
            return "unknown";
        }

        int mask = prefixLength == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefixLength));
        return ((mask >>> 24) & 0xff) + "." + ((mask >>> 16) & 0xff) + "." + ((mask >>> 8) & 0xff) + "." + (mask & 0xff);
    }

    private String determineInterfacePath(NetworkInterface ni) {
        List<String> segments = new ArrayList<String>();
        NetworkInterface current = ni;
        while (current != null) {
            segments.add(current.getName());
            current = current.getParent();
        }
        Collections.reverse(segments);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(segments.get(i));
        }
        return sb.toString();
    }

    private String formatMacAddress(NetworkInterface ni) {
        try {
            byte[] mac = ni.getHardwareAddress();
            if (mac == null || mac.length == 0) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mac.length; i++) {
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(toHex((mac[i] >> 4) & 0xF));
                sb.append(toHex(mac[i] & 0xF));
            }
            return sb.toString();
        } catch (Exception ex) {
            return "";
        }
    }

    private char toHex(int value) {
        int v = value & 0xF;
        return (char) (v < 10 ? ('0' + v) : ('A' + (v - 10)));
    }

    private List<ProcessStatus> collectProcessStatuses() {
        if (processWatchList.isEmpty()) {
            return Collections.emptyList();
        }

        LinkedHashMap<String, String> normalizedNames = new LinkedHashMap<String, String>();
        for (int i = 0; i < processWatchList.size(); i++) {
            String original = processWatchList.get(i);
            if (original == null) {
                continue;
            }
            String trimmed = original.trim();
            if (trimmed.length() == 0) {
                continue;
            }
            String key = trimmed.toLowerCase(Locale.ROOT);
            if (!normalizedNames.containsKey(key)) {
                normalizedNames.put(key, trimmed);
            }
        }

        Map<String, Set<Long>> found = new HashMap<String, Set<Long>>();
        List<String> lines = executeCommand(new String[] {"tasklist", "/FO", "CSV", "/NH"}, 3000L);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.length() == 0 || trimmed.toLowerCase(Locale.ROOT).startsWith("info:")) {
                continue;
            }
            List<String> cols = parseCsvLine(trimmed);
            if (cols.size() < 2) {
                continue;
            }
            String name = cols.get(0).trim().toLowerCase(Locale.ROOT);
            String pidRaw = cols.get(1).trim();
            if (!normalizedNames.containsKey(name)) {
                continue;
            }
            try {
                Long pid = Long.valueOf(Long.parseLong(pidRaw));
                Set<Long> ids = found.get(name);
                if (ids == null) {
                    ids = new LinkedHashSet<Long>();
                    found.put(name, ids);
                }
                ids.add(pid);
            } catch (NumberFormatException ignored) {
            }
        }

        List<ProcessStatus> result = new ArrayList<ProcessStatus>();
        for (Map.Entry<String, String> entry : normalizedNames.entrySet()) {
            Set<Long> ids = found.get(entry.getKey());
            List<Long> ordered = ids == null ? Collections.<Long>emptyList() : new ArrayList<Long>(ids);
            result.add(new ProcessStatus(entry.getValue(), !ordered.isEmpty(), ordered));
        }
        return result;
    }

    private List<String> parseCsvLine(String line) {
        List<String> cols = new ArrayList<String>();
        StringBuffer current = new StringBuffer();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
                continue;
            }
            if (c == ',' && !inQuotes) {
                cols.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        cols.add(current.toString());
        return cols;
    }

    private List<String> readPlainListFile(File file) {
        if (file == null || !file.exists()) {
            LOG.warn("[SystemMonitor] Archivo no encontrado: {}", file);
            return Collections.emptyList();
        }

        List<String> lines = new ArrayList<String>();
        InputStream in = null;
        BufferedReader reader = null;
        try {
            in = new FileInputStream(file);
            reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.length() == 0 || trimmed.startsWith("#")) {
                    continue;
                }
                lines.add(trimmed);
            }
        } catch (IOException ex) {
            LOG.warn("[SystemMonitor] No se pudo leer archivo {}", file, ex);
        } finally {
            closeQuietly(reader);
            closeQuietly(in);
        }
        return lines;
    }

    private List<String> executeCommand(String[] command, long timeoutMs) {
        List<String> lines = new ArrayList<String>();
        Process process = null;
        InputStream stream = null;
        BufferedReader reader = null;

        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            stream = process.getInputStream();
            reader = new BufferedReader(new InputStreamReader(stream, "Cp1252"));

            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(stripBom(line));
            }
            if (!waitFor(process, timeoutMs)) {
                LOG.warn("[SystemMonitor] Comando excedio timeout: {}", joinCommand(command));
                process.destroy();
            }
            return lines;
        } catch (Exception ex) {
            LOG.warn("[SystemMonitor] Error ejecutando comando: {}", joinCommand(command), ex);
            return lines;
        } finally {
            closeQuietly(reader);
            closeQuietly(stream);
            if (process != null) {
                try {
                    process.getOutputStream().close();
                } catch (IOException ignored) {
                }
                try {
                    process.getErrorStream().close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private boolean waitFor(Process process, long timeoutMs) {
        long started = System.currentTimeMillis();
        while (true) {
            try {
                process.exitValue();
                return true;
            } catch (IllegalThreadStateException running) {
                if (System.currentTimeMillis() - started >= timeoutMs) {
                    return false;
                }
                try {
                    Thread.sleep(40L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    private String joinCommand(String[] command) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < command.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(command[i]);
        }
        return sb.toString();
    }

    private String stripBom(String value) {
        if (value != null && value.length() > 0 && value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
    }

    private static long safeMultiply(long a, long b) {
        if (a <= 0 || b <= 0) {
            return 0L;
        }
        if (a > (Long.MAX_VALUE / b)) {
            return Long.MAX_VALUE;
        }
        return a * b;
    }

    private String utcNowIso() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date());
    }

    private void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException ignored) {
        }
    }

    private void closeQuietly(BufferedReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (IOException ignored) {
        }
    }

    public void close() {
        stop();
        executor.shutdown();
        try {
            executor.awaitTermination(2L, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class MonitorThreadFactory implements ThreadFactory {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "system-monitor");
            t.setDaemon(true);
            return t;
        }
    }

    private static final class MemoryInfo {
        private final long totalBytes;
        private final long freeBytes;

        private MemoryInfo(long totalBytes, long freeBytes) {
            this.totalBytes = totalBytes;
            this.freeBytes = freeBytes;
        }
    }
}
