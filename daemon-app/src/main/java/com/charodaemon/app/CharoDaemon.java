package com.charodaemon.app;

import com.charodaemon.monitor.SystemMonitor;
import com.charodaemon.monitor.config.MonitorSettings;
import com.charodaemon.rest.RestApiServer;
import com.charodaemon.rest.RestServerConfig;
import com.charodaemon.mqtt.MetricsAveragingPublisher;
import com.charodaemon.mqtt.MqttPublisherConfig;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.ComputerSystem;
import oshi.hardware.HardwareAbstractionLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CharoDaemon {
    private static final Logger LOG = LoggerFactory.getLogger(CharoDaemon.class);
    private CharoDaemon() {
    }

    public static void main(String[] args) {
        // Log uncaught exceptions from any thread
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                LOG.error("Uncaught exception in thread {}", t.getName(), e);
            } catch (Throwable ignored) {
                e.printStackTrace(System.err);
            }
        });

        try {
            Path configPath = Paths.get("config", "daemon.properties");
            DaemonConfiguration configuration = loadConfiguration(configPath);
            String resolvedClientId = resolveClientId(configuration);

        MonitorSettings.Builder monitorBuilder = MonitorSettings.builder()
                .samplingInterval(configuration.monitorInterval());
        configuration.processWatchListPath().ifPresent(monitorBuilder::processWatchListPath);
        configuration.networkInterfaceExcludePath().ifPresent(monitorBuilder::networkInterfaceExcludePath);

            SystemMonitor monitor = new SystemMonitor(monitorBuilder.build());
            monitor.start();

        MqttPublisherConfig.Builder mqttBuilder = MqttPublisherConfig.builder()
                .brokerUri(configuration.mqttBrokerUri())
                .clientId(resolvedClientId)
                .sampleWindow(configuration.mqttSampleWindow())
                .pollingInterval(configuration.monitorInterval());
        configuration.mqttTopicTemplate().ifPresent(mqttBuilder::topicTemplate);
        configuration.mqttUsername().ifPresent(mqttBuilder::username);
        configuration.mqttPassword().ifPresent(mqttBuilder::password);
        mqttBuilder.availabilityEnabled(configuration.mqttAvailabilityEnabled());
        configuration.mqttAvailabilityTopic().ifPresent(mqttBuilder::availabilityTopic);
        mqttBuilder.retainAvailability(configuration.mqttRetainAvailability());
            MqttPublisherConfig mqttConfig = mqttBuilder.build();
            MetricsAveragingPublisher publisher = new MetricsAveragingPublisher(monitor, mqttConfig);

            RestApiServer restServer = new RestApiServer(
                monitor,
                RestServerConfig.builder()
                        .port(configuration.restPort())
                        .instanceId(resolvedClientId)
                        .build(),
                publisher::latestSnapshot,
                configuration.monitorInterval().getSeconds() * configuration.mqttSampleWindow()
        );
            restServer.start();
            publisher.start();

            CountDownLatch shutdownLatch = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.info("[Daemon] Shutting down...");
                try { publisher.signalOffline(); } catch (Exception e) { LOG.warn("Error signalling offline", e); }
                try { publisher.close(); } catch (Exception e) { LOG.warn("Error closing publisher", e); }
                try { restServer.close(); } catch (Exception e) { LOG.warn("Error closing REST server", e); }
                try { monitor.close(); } catch (Exception e) { LOG.warn("Error closing monitor", e); }
                shutdownLatch.countDown();
            }, "daemon-shutdown"));

            LOG.info("[Daemon] Running with config from {}", configPath.toAbsolutePath());
            LOG.info("[Daemon] Press Ctrl+C to stop.");
            shutdownLatch.await();
        } catch (Exception ex) {
            try {
                LOG.error("Fatal error during daemon execution", ex);
            } finally {
                // ensure non-zero exit to signal supervising systems
                System.exit(1);
            }
        }
    }

    private static DaemonConfiguration loadConfiguration(Path configPath) throws IOException {
        if (Files.notExists(configPath)) {
            throw new IOException("Config file not found: " + configPath.toAbsolutePath());
        }
        return DaemonConfiguration.load(configPath);
    }

    private static String resolveClientId(DaemonConfiguration configuration) {
        String baseId = sanitize(configuration.mqttClientId());
        String hostName = sanitize(detectHostName());
        String fingerprint = sanitize(computeHardwareFingerprint());

        StringBuilder builder = new StringBuilder();
        if (!baseId.isBlank()) {
            builder.append(baseId);
        }
        if (!hostName.isBlank()) {
            if (builder.length() > 0) {
                builder.append("-");
            }
            builder.append(hostName);
        }
        if (!fingerprint.isBlank()) {
            if (builder.length() > 0) {
                builder.append("-");
            }
            builder.append(fingerprint);
        }
        if (builder.length() == 0) {
            return "charodaemon-" + Long.toHexString(System.currentTimeMillis());
        }
        return builder.toString();
    }

    private static String detectHostName() {
        String[] candidates = {
                System.getenv("CHARODAEMON_HOST_ID"),
                System.getenv("HOSTNAME"),
                System.getenv("COMPUTERNAME"),
                tryGetLocalHostName(),
                InetAddress.getLoopbackAddress().getHostName()
        };
        for (String candidate : candidates) {
            if (isMeaningful(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    private static String computeHardwareFingerprint() {
        
        SystemInfo info = new SystemInfo();
        HardwareAbstractionLayer hal = info.getHardware();
        ComputerSystem computerSystem = hal.getComputerSystem();
               
        String uuid = computerSystem.getHardwareUUID();
    
        if (isMeaningfulHardwareId(uuid)) {
            return shortHash(uuid);
        }
    
        return "";      
    }

    private static String shortHash(String input) {
       
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            
            for (int i = 0; i < bytes.length && builder.length() < 6; i++) {
                builder.append(String.format(Locale.ROOT, "%02x", bytes[i]));                
            }
            if (builder.length() > 0) {
                return builder.toString();
            }
        } catch (NoSuchAlgorithmException ignored) {
            // Falls back to sanitized input below
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
 
    private static boolean isMeaningfulHardwareId(String value) {
        if (!isMeaningful(value)) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return !normalized.contains("to be filled") && !normalized.contains("Default string")
                && !normalized.contains("not specified");
    }

    private static boolean isMeaningful(String value) {
        return value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value);
    }
    
    private static String sanitize(String input) {
        if (input == null) {
            return "";
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        return trimmed.replaceAll("[^a-zA-Z0-9_-]", "-").toLowerCase(Locale.ROOT);
    }
}
