package com.charodaemon.app;

import com.charodaemon.monitor.SystemMonitor;
import com.charodaemon.monitor.config.MonitorSettings;
import com.charodaemon.mqtt.MetricsAveragingPublisher;
import com.charodaemon.mqtt.MqttPublisherConfig;
import com.charodaemon.rest.RestApiServer;
import com.charodaemon.rest.RestServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

public final class CharoDaemon {
    private static final Logger LOG = LoggerFactory.getLogger(CharoDaemon.class);

    private CharoDaemon() {
    }

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    LOG.error("Uncaught exception in thread {}", t.getName(), e);
                } catch (Throwable ignored) {
                    e.printStackTrace(System.err);
                }
            }
        });

        try {
            File installRoot = detectInstallRoot();
            File configFile = new File(new File(installRoot, "config"), "daemon.properties").getAbsoluteFile();
            DaemonConfiguration configuration = DaemonConfiguration.load(configFile);
            String resolvedClientId = resolveClientId(configuration);

            MonitorSettings monitorSettings = MonitorSettings.builder()
                    .samplingIntervalSeconds(configuration.monitorIntervalSeconds())
                    .processWatchListPath(configuration.processWatchListPath())
                    .networkInterfaceExcludePath(configuration.networkInterfaceExcludePath())
                    .build();

            final SystemMonitor monitor = new SystemMonitor(monitorSettings);
            monitor.start();

            MqttPublisherConfig mqttConfig = MqttPublisherConfig.builder()
                    .brokerUri(configuration.mqttBrokerUri())
                    .clientId(resolvedClientId)
                    .topicTemplate(configuration.mqttTopicTemplate())
                    .sampleWindow(configuration.mqttSampleWindow())
                    .publishEveryHttpUpdates(configuration.mqttPublishEveryHttpUpdates())
                    .pollingIntervalSeconds(configuration.monitorIntervalSeconds())
                    .username(configuration.mqttUsername())
                    .password(configuration.mqttPassword())
                    .availabilityEnabled(configuration.mqttAvailabilityEnabled())
                    .availabilityTopic(configuration.mqttAvailabilityTopic())
                    .retainAvailability(configuration.mqttRetainAvailability())
                    .build();

            final MetricsAveragingPublisher publisher = new MetricsAveragingPublisher(monitor, mqttConfig);

            RestServerConfig restConfig = RestServerConfig.builder()
                    .port(configuration.restPort())
                    .instanceId(resolvedClientId)
                    .build();

            final RestApiServer restServer = new RestApiServer(monitor, restConfig, new RestApiServer.SnapshotProvider() {
                public com.charodaemon.monitor.model.AggregatedMetricsSnapshot latestSnapshot() {
                    return publisher.latestSnapshot();
                }
            });

            restServer.start();
            publisher.start();

            final CountDownLatch shutdownLatch = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                public void run() {
                    LOG.info("[Daemon] Shutting down...");
                    try {
                        publisher.signalOffline();
                    } catch (Exception ex) {
                        LOG.warn("Error signalling offline", ex);
                    }
                    try {
                        publisher.close();
                    } catch (Exception ex) {
                        LOG.warn("Error closing publisher", ex);
                    }
                    try {
                        restServer.close();
                    } catch (Exception ex) {
                        LOG.warn("Error closing REST server", ex);
                    }
                    try {
                        monitor.close();
                    } catch (Exception ex) {
                        LOG.warn("Error closing monitor", ex);
                    }
                    shutdownLatch.countDown();
                }
            }, "daemon-shutdown"));

            LOG.info("[Daemon] Running with config from {}", configFile.getAbsolutePath());
            LOG.info("[Daemon] Press Ctrl+C to stop.");
            shutdownLatch.await();
        } catch (Exception ex) {
            LOG.error("Fatal error during daemon execution", ex);
            System.exit(1);
        }
    }

    private static String resolveClientId(DaemonConfiguration configuration) {
        String baseId = sanitize(configuration.mqttClientId());
        String hostName = sanitize(detectHostName());
        String fingerprint = sanitize(computeHardwareFingerprint());

        StringBuilder sb = new StringBuilder();
        appendPart(sb, baseId);
        appendPart(sb, hostName);
        appendPart(sb, fingerprint);

        if (sb.length() == 0) {
            return "charodaemon-" + Long.toHexString(System.currentTimeMillis());
        }
        return sb.toString();
    }

    private static void appendPart(StringBuilder sb, String part) {
        if (part == null || part.length() == 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('-');
        }
        sb.append(part);
    }

    private static File detectInstallRoot() {
        try {
            File location = new File(CharoDaemon.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File current = location.isDirectory() ? location : location.getParentFile();
            while (current != null) {
                File configDir = new File(current, "config");
                File configFile = new File(configDir, "daemon.properties");
                if (configDir.isDirectory() && configFile.exists()) {
                    return current.getAbsoluteFile();
                }
                current = current.getParentFile();
            }
        } catch (Exception ignored) {
        }
        return new File(".").getAbsoluteFile();
    }

    private static String detectHostName() {
        String[] candidates = new String[] {
                System.getenv("CHARODAEMON_HOST_ID"),
                System.getenv("HOSTNAME"),
                System.getenv("COMPUTERNAME"),
                tryGetLocalHostName()
        };
        for (int i = 0; i < candidates.length; i++) {
            if (isMeaningful(candidates[i])) {
                return candidates[i];
            }
        }
        return "";
    }

    private static String tryGetLocalHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String computeHardwareFingerprint() {
        try {
            List<String> sources = new ArrayList<String>();
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface ni = interfaces.nextElement();
                    byte[] mac = ni.getHardwareAddress();
                    if (mac == null || mac.length == 0) {
                        continue;
                    }
                    sources.add(toHex(mac));
                }
            }
            if (sources.isEmpty()) {
                return "";
            }
            Collections.sort(sources);
            StringBuilder seed = new StringBuilder();
            for (int i = 0; i < sources.size(); i++) {
                if (i > 0) {
                    seed.append('|');
                }
                seed.append(sources.get(i));
            }
            return shortHash(seed.toString());
        } catch (Exception ex) {
            return "";
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            if (i > 0) {
                sb.append(':');
            }
            sb.append(hexNibble((b >> 4) & 0xF));
            sb.append(hexNibble(b & 0xF));
        }
        return sb.toString();
    }

    private static char hexNibble(int value) {
        return (char) (value < 10 ? ('0' + value) : ('a' + (value - 10)));
    }

    private static String shortHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hash.length && sb.length() < 6; i++) {
                int b = hash[i] & 0xFF;
                sb.append(hexNibble((b >> 4) & 0xF));
                sb.append(hexNibble(b & 0xF));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            return "";
        } catch (Exception ex) {
            return "";
        }
    }

    private static boolean isMeaningful(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.length() > 0 && !"unknown".equalsIgnoreCase(trimmed);
    }

    private static String sanitize(String input) {
        if (input == null) {
            return "";
        }
        String trimmed = input.trim();
        if (trimmed.length() == 0) {
            return "";
        }
        return trimmed.replaceAll("[^a-zA-Z0-9_-]", "-").toLowerCase(Locale.ROOT);
    }
}
