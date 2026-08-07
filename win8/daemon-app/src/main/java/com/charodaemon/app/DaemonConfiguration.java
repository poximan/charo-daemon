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
    private final String mqttTopicTemplate;
    private final int mqttSampleWindow;
    private final int mqttPublishEveryHttpUpdates;
    private final String mqttClientId;
    private final String mqttUsername;
    private final String mqttPassword;
    private final boolean mqttAvailabilityEnabled;
    private final String mqttAvailabilityTopic;
    private final boolean mqttRetainAvailability;
    private DaemonConfiguration(Builder builder) {
        this.monitorInterval = builder.monitorInterval;
        this.processWatchListPath = builder.processWatchListPath;
        this.networkInterfaceExcludePath = builder.networkInterfaceExcludePath;
        this.restPort = builder.restPort;
        this.mqttBrokerUri = builder.mqttBrokerUri;
        this.mqttTopicTemplate = builder.mqttTopicTemplate;
        this.mqttSampleWindow = builder.mqttSampleWindow;
        this.mqttPublishEveryHttpUpdates = builder.mqttPublishEveryHttpUpdates;
        this.mqttClientId = builder.mqttClientId;
        this.mqttUsername = builder.mqttUsername;
        this.mqttPassword = builder.mqttPassword;
        this.mqttAvailabilityEnabled = builder.mqttAvailabilityEnabled;
        this.mqttAvailabilityTopic = builder.mqttAvailabilityTopic;
        this.mqttRetainAvailability = builder.mqttRetainAvailability;
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

    public Optional<String> mqttTopicTemplate() { return Optional.ofNullable(mqttTopicTemplate); }

    public int mqttSampleWindow() {
        return mqttSampleWindow;
    }

    public int mqttPublishEveryHttpUpdates() {
        return mqttPublishEveryHttpUpdates;
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

    public boolean mqttAvailabilityEnabled() { return mqttAvailabilityEnabled; }

    public Optional<String> mqttAvailabilityTopic() { return Optional.ofNullable(mqttAvailabilityTopic); }

    public boolean mqttRetainAvailability() { return mqttRetainAvailability; }

    public static DaemonConfiguration load(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
        }
        return fromProperties(path.getParent(), properties);
    }

    public static DaemonConfiguration fromProperties(Path baseDir, Properties properties) throws IOException {
        Builder builder = new Builder();
        long intervalSeconds = requireLong(properties, "monitor.interval.seconds", 1);
        builder.monitorInterval(Duration.ofSeconds(intervalSeconds));
        Path processPath = requirePath(properties, baseDir, "monitor.process.watchlist");
        builder.processWatchListPath(processPath);
        Path ifaceExcludePath = requirePath(properties, baseDir, "monitor.network.interface.exclude");
        builder.networkInterfaceExcludePath(ifaceExcludePath);
        int port = Math.toIntExact(requireLong(properties, "rest.port", 1));
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("rest.port fuera de rango: " + port);
        }
        builder.restPort(port);
        builder.mqttBrokerUri(requireString(properties, "mqtt.broker.uri"));
        builder.mqttTopicTemplate(requireString(properties, "mqtt.topic.template"));
        builder.mqttSampleWindow(Math.toIntExact(requireLong(properties, "mqtt.sample.window", 1)));
        builder.mqttPublishEveryHttpUpdates(Math.toIntExact(requireLong(properties, "mqtt.publish.every.http.updates", 1)));
        builder.mqttClientId(requireString(properties, "mqtt.client.id"));
        builder.mqttUsername(requireString(properties, "mqtt.username"));
        builder.mqttPassword(requireString(properties, "mqtt.password"));
        boolean availabilityEnabled = requireBoolean(properties, "mqtt.availability.enabled");
        builder.mqttAvailabilityEnabled(availabilityEnabled);
        builder.mqttAvailabilityTopic(requireString(properties, "mqtt.availability.topic"));
        builder.mqttRetainAvailability(requireBoolean(properties, "mqtt.retain.availability"));
        return builder.build();
    }

    private static long requireLong(Properties props, String key, long minValue) {
        String raw = props.getProperty(key);
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("Falta la propiedad obligatoria: " + key);
        }
        try {
            long v = Long.parseLong(raw.trim());
            if (v < minValue) {
                throw new IllegalArgumentException("Valor invalido (" + v + ") para " + key);
            }
            return v;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("No es numero valido: " + key + "='" + raw + "'", ex);
        }
    }

    private static boolean requireBoolean(Properties props, String key) {
        String raw = props.getProperty(key);
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("Falta la propiedad obligatoria: " + key);
        }
        String t = raw.trim().toLowerCase();
        if ("true".equals(t)) return true;
        if ("false".equals(t)) return false;
        throw new IllegalArgumentException("Valor booleano invalido para " + key + ": '" + raw + "'");
    }

    private static String requireString(Properties props, String key) {
        String raw = props.getProperty(key);
        if (raw == null) {
            throw new IllegalArgumentException("Falta la propiedad obligatoria: " + key);
        }
        String val = raw.trim();
        if (val.isEmpty()) {
            throw new IllegalArgumentException("Propiedad vacia: " + key);
        }
        return val;
    }

    private static Path requirePath(Properties props, Path baseDir, String key) throws IOException {
        String rel = requireString(props, key);
        Path p = baseDir != null ? baseDir.resolve(rel).normalize() : Paths.get(rel).normalize();
        if (!Files.exists(p)) {
            throw new IOException("No existe el archivo configurado en " + key + ": " + p.toAbsolutePath());
        }
        return p;
    }

    public static Builder builder() {
        return new Builder();
    }
    public static final class Builder {
        private Duration monitorInterval;
        private Path processWatchListPath;
        private Path networkInterfaceExcludePath;
        private Integer restPort;
        private String mqttBrokerUri;
        private String mqttTopicTemplate;
        private Integer mqttSampleWindow;
        private Integer mqttPublishEveryHttpUpdates;
        private String mqttClientId;
        private String mqttUsername;
        private String mqttPassword;
        private Boolean mqttAvailabilityEnabled;
        private String mqttAvailabilityTopic;
        private Boolean mqttRetainAvailability;
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

        public Builder mqttTopicTemplate(String mqttTopicTemplate) {
            this.mqttTopicTemplate = Objects.requireNonNull(mqttTopicTemplate, "mqttTopicTemplate");
            return this;
        }

        public Builder mqttSampleWindow(int mqttSampleWindow) {
            this.mqttSampleWindow = mqttSampleWindow;
            return this;
        }

        public Builder mqttPublishEveryHttpUpdates(int mqttPublishEveryHttpUpdates) {
            this.mqttPublishEveryHttpUpdates = mqttPublishEveryHttpUpdates;
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

        public Builder mqttAvailabilityEnabled(boolean enabled) {
            this.mqttAvailabilityEnabled = enabled;
            return this;
        }

        public Builder mqttAvailabilityTopic(String topic) {
            this.mqttAvailabilityTopic = Objects.requireNonNull(topic, "mqttAvailabilityTopic");
            return this;
        }

        public Builder mqttRetainAvailability(boolean retain) {
            this.mqttRetainAvailability = retain;
            return this;
        }

        public DaemonConfiguration build() {
            if (monitorInterval == null) throw new IllegalStateException("monitor.interval.seconds requerido");
            if (processWatchListPath == null) throw new IllegalStateException("monitor.process.watchlist requerido");
            if (networkInterfaceExcludePath == null) throw new IllegalStateException("monitor.network.interface.exclude requerido");
            if (restPort == null || restPort <= 0 || restPort > 65535) throw new IllegalStateException("rest.port invalido");
            if (mqttBrokerUri == null || mqttBrokerUri.isBlank()) throw new IllegalStateException("mqtt.broker.uri requerido");
            if (mqttTopicTemplate == null || mqttTopicTemplate.isBlank()) throw new IllegalStateException("mqtt.topic.template requerido");
            if (mqttSampleWindow == null || mqttSampleWindow <= 0) throw new IllegalStateException("mqtt.sample.window invalido");
            if (mqttPublishEveryHttpUpdates == null || mqttPublishEveryHttpUpdates <= 0) throw new IllegalStateException("mqtt.publish.every.http.updates invalido");
            if (mqttClientId == null || mqttClientId.isBlank()) throw new IllegalStateException("mqtt.client.id requerido");
            if (mqttUsername == null || mqttUsername.isBlank()) throw new IllegalStateException("mqtt.username requerido");
            if (mqttPassword == null || mqttPassword.isBlank()) throw new IllegalStateException("mqtt.password requerido");
            if (mqttAvailabilityEnabled == null) throw new IllegalStateException("mqtt.availability.enabled requerido");
            if (mqttAvailabilityTopic == null || mqttAvailabilityTopic.isBlank()) throw new IllegalStateException("mqtt.availability.topic requerido");
            if (mqttRetainAvailability == null) throw new IllegalStateException("mqtt.retain.availability requerido");
            return new DaemonConfiguration(this);
        }
    }
}
