package com.charodaemon.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

public final class DaemonConfiguration {
    private final Duration monitorInterval;
    private final Path processWatchListPath;
    private final Path networkInterfaceExcludePath;
    private final int restPort;
    private final String mqttBrokerUri;
    private final String mqttTopic;
    private final int mqttSampleWindow;
    private final String mqttClientId;
    private final String mqttUsername;
    private final String mqttPassword;

    private DaemonConfiguration(Builder builder) {
        this.monitorInterval = builder.monitorInterval;
        this.processWatchListPath = builder.processWatchListPath;
        this.networkInterfaceExcludePath = builder.networkInterfaceExcludePath;
        this.restPort = builder.restPort;
        this.mqttBrokerUri = builder.mqttBrokerUri;
        this.mqttTopic = builder.mqttTopic;
        this.mqttSampleWindow = builder.mqttSampleWindow;
        this.mqttClientId = builder.mqttClientId;
        this.mqttUsername = builder.mqttUsername;
        this.mqttPassword = builder.mqttPassword;
    }

    public Duration monitorInterval() {
        return monitorInterval;
    }

    public Optional<Path> processWatchListPath() {
        return Optional.ofNullable(processWatchListPath);
    }

    public Optional<Path> networkInterfaceExcludePath() {
        return Optional.ofNullable(networkInterfaceExcludePath);
    }

    public int restPort() {
        return restPort;
    }

    public String mqttBrokerUri() {
        return mqttBrokerUri;
    }

    public String mqttTopic() {
        return mqttTopic;
    }

    public int mqttSampleWindow() {
        return mqttSampleWindow;
    }

    public String mqttClientId() {
        return mqttClientId;
    }

    public Optional<String> mqttUsername() {
        return Optional.ofNullable(mqttUsername);
    }

    public Optional<String> mqttPassword() {
        return Optional.ofNullable(mqttPassword);
    }

    public static DaemonConfiguration load(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
        }
        return fromProperties(path.getParent(), properties);
    }

    public static DaemonConfiguration fromProperties(Path baseDir, Properties properties) {
        Builder builder = new Builder();

        long intervalSeconds = parseLong(properties.getProperty("monitor.interval.seconds"), 20);
        builder.monitorInterval(Duration.ofSeconds(Math.max(1, intervalSeconds)));

        String processFile = properties.getProperty("monitor.process.watchlist");
        if (processFile != null && !processFile.isBlank() && baseDir != null) {
            builder.processWatchListPath(baseDir.resolve(processFile.trim()));
        } else if (processFile != null && !processFile.isBlank()) {
            builder.processWatchListPath(Paths.get(processFile.trim()));
        }

        String interfaceExcludeFile = properties.getProperty("monitor.network.interface.exclude");
        if (interfaceExcludeFile != null && !interfaceExcludeFile.isBlank() && baseDir != null) {
            builder.networkInterfaceExcludePath(baseDir.resolve(interfaceExcludeFile.trim()));
        } else if (interfaceExcludeFile != null && !interfaceExcludeFile.isBlank()) {
            builder.networkInterfaceExcludePath(Paths.get(interfaceExcludeFile.trim()));
        }

        int port = (int) parseLong(properties.getProperty("rest.port"), 8080);
        builder.restPort(port);

        builder.mqttBrokerUri(properties.getProperty("mqtt.broker.uri", "tcp://localhost:1883"));
        builder.mqttTopic(properties.getProperty("mqtt.topic", "charodaemon/metrics"));
        builder.mqttSampleWindow((int) parseLong(properties.getProperty("mqtt.sample.window"), 5));
        builder.mqttClientId(properties.getProperty("mqtt.client.id"));
        String mqttUsername = trimToNull(properties.getProperty("mqtt.username"));
        if (mqttUsername != null) {
            builder.mqttUsername(mqttUsername);
        }
        String mqttPassword = trimToNull(properties.getProperty("mqtt.password"));
        if (mqttPassword != null) {
            builder.mqttPassword(mqttPassword);
        }

        return builder.build();
    }

    private static long parseLong(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Duration monitorInterval = Duration.ofSeconds(20);
        private Path processWatchListPath;
        private Path networkInterfaceExcludePath;
        private int restPort = 8080;
        private String mqttBrokerUri = "tcp://localhost:1883";
        private String mqttTopic = "charodaemon/metrics";
        private int mqttSampleWindow = 5;
        private String mqttClientId = "charo-daemon";
        private String mqttUsername;
        private String mqttPassword;

        private Builder() {
        }

        public Builder monitorInterval(Duration monitorInterval) {
            this.monitorInterval = Objects.requireNonNull(monitorInterval, "monitorInterval");
            return this;
        }

        public Builder processWatchListPath(Path processWatchListPath) {
            this.processWatchListPath = processWatchListPath;
            return this;
        }

        public Builder networkInterfaceExcludePath(Path networkInterfaceExcludePath) {
            this.networkInterfaceExcludePath = networkInterfaceExcludePath;
            return this;
        }

        public Builder restPort(int restPort) {
            this.restPort = restPort;
            return this;
        }

        public Builder mqttBrokerUri(String mqttBrokerUri) {
            this.mqttBrokerUri = Objects.requireNonNull(mqttBrokerUri, "mqttBrokerUri");
            return this;
        }

        public Builder mqttTopic(String mqttTopic) {
            this.mqttTopic = Objects.requireNonNull(mqttTopic, "mqttTopic");
            return this;
        }

        public Builder mqttSampleWindow(int mqttSampleWindow) {
            this.mqttSampleWindow = mqttSampleWindow;
            return this;
        }

        public Builder mqttClientId(String mqttClientId) {
            this.mqttClientId = Objects.requireNonNull(mqttClientId, "mqttClientId");
            return this;
        }

        public Builder mqttUsername(String mqttUsername) {
            this.mqttUsername = Objects.requireNonNull(mqttUsername, "mqttUsername");
            return this;
        }

        public Builder mqttPassword(String mqttPassword) {
            this.mqttPassword = Objects.requireNonNull(mqttPassword, "mqttPassword");
            return this;
        }

        public DaemonConfiguration build() {
            return new DaemonConfiguration(this);
        }
    }
}
