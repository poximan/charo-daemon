package com.charodaemon.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class DaemonConfiguration {
    private final long monitorIntervalSeconds;
    private final File processWatchListPath;
    private final File networkInterfaceExcludePath;
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
        this.monitorIntervalSeconds = builder.monitorIntervalSeconds.longValue();
        this.processWatchListPath = builder.processWatchListPath;
        this.networkInterfaceExcludePath = builder.networkInterfaceExcludePath;
        this.restPort = builder.restPort.intValue();
        this.mqttBrokerUri = builder.mqttBrokerUri;
        this.mqttTopicTemplate = builder.mqttTopicTemplate;
        this.mqttSampleWindow = builder.mqttSampleWindow.intValue();
        this.mqttPublishEveryHttpUpdates = builder.mqttPublishEveryHttpUpdates.intValue();
        this.mqttClientId = builder.mqttClientId;
        this.mqttUsername = builder.mqttUsername;
        this.mqttPassword = builder.mqttPassword;
        this.mqttAvailabilityEnabled = builder.mqttAvailabilityEnabled.booleanValue();
        this.mqttAvailabilityTopic = builder.mqttAvailabilityTopic;
        this.mqttRetainAvailability = builder.mqttRetainAvailability.booleanValue();
    }

    public long monitorIntervalSeconds() {
        return monitorIntervalSeconds;
    }

    public File processWatchListPath() {
        return processWatchListPath;
    }

    public File networkInterfaceExcludePath() {
        return networkInterfaceExcludePath;
    }

    public int restPort() {
        return restPort;
    }

    public String mqttBrokerUri() {
        return mqttBrokerUri;
    }

    public String mqttTopicTemplate() {
        return mqttTopicTemplate;
    }

    public int mqttSampleWindow() {
        return mqttSampleWindow;
    }

    public int mqttPublishEveryHttpUpdates() {
        return mqttPublishEveryHttpUpdates;
    }

    public String mqttClientId() {
        return mqttClientId;
    }

    public String mqttUsername() {
        return mqttUsername;
    }

    public String mqttPassword() {
        return mqttPassword;
    }

    public boolean mqttAvailabilityEnabled() {
        return mqttAvailabilityEnabled;
    }

    public String mqttAvailabilityTopic() {
        return mqttAvailabilityTopic;
    }

    public boolean mqttRetainAvailability() {
        return mqttRetainAvailability;
    }

    public static DaemonConfiguration load(File file) throws IOException {
        if (file == null) {
            throw new IOException("Config file null");
        }
        if (!file.exists()) {
            throw new IOException("Config file not found: " + file.getAbsolutePath());
        }

        Properties properties = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            properties.load(in);
        } finally {
            if (in != null) {
                in.close();
            }
        }

        File baseDir = file.getParentFile();
        return fromProperties(baseDir, properties);
    }

    public static DaemonConfiguration fromProperties(File baseDir, Properties props) throws IOException {
        Builder builder = new Builder();

        builder.monitorIntervalSeconds(requireLong(props, "monitor.interval.seconds", 1L));
        builder.processWatchListPath(requireFile(baseDir, requireString(props, "monitor.process.watchlist"), "monitor.process.watchlist"));
        builder.networkInterfaceExcludePath(requireFile(baseDir, requireString(props, "monitor.network.interface.exclude"), "monitor.network.interface.exclude"));

        int port = (int) requireLong(props, "rest.port", 1L);
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("rest.port fuera de rango: " + port);
        }
        builder.restPort(port);

        builder.mqttBrokerUri(requireString(props, "mqtt.broker.uri"));
        builder.mqttTopicTemplate(requireString(props, "mqtt.topic.template"));
        builder.mqttSampleWindow((int) requireLong(props, "mqtt.sample.window", 1L));
        builder.mqttPublishEveryHttpUpdates((int) requireLong(props, "mqtt.publish.every.http.updates", 1L));
        builder.mqttClientId(requireString(props, "mqtt.client.id"));
        builder.mqttUsername(requireString(props, "mqtt.username"));
        builder.mqttPassword(requireString(props, "mqtt.password"));
        builder.mqttAvailabilityEnabled(requireBoolean(props, "mqtt.availability.enabled"));
        builder.mqttAvailabilityTopic(requireString(props, "mqtt.availability.topic"));
        builder.mqttRetainAvailability(requireBoolean(props, "mqtt.retain.availability"));

        return builder.build();
    }

    private static long requireLong(Properties props, String key, long minValue) {
        String raw = props.getProperty(key);
        if (raw == null || raw.trim().length() == 0) {
            throw new IllegalArgumentException("Falta propiedad obligatoria: " + key);
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value < minValue) {
                throw new IllegalArgumentException("Valor invalido para " + key + ": " + value);
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("No es numero valido para " + key + ": " + raw, ex);
        }
    }

    private static boolean requireBoolean(Properties props, String key) {
        String raw = props.getProperty(key);
        if (raw == null || raw.trim().length() == 0) {
            throw new IllegalArgumentException("Falta propiedad obligatoria: " + key);
        }
        String val = raw.trim().toLowerCase();
        if ("true".equals(val)) {
            return true;
        }
        if ("false".equals(val)) {
            return false;
        }
        throw new IllegalArgumentException("Booleano invalido para " + key + ": " + raw);
    }

    private static String requireString(Properties props, String key) {
        String raw = props.getProperty(key);
        if (raw == null) {
            throw new IllegalArgumentException("Falta propiedad obligatoria: " + key);
        }
        String value = raw.trim();
        if (value.length() == 0) {
            throw new IllegalArgumentException("Propiedad vacia: " + key);
        }
        return value;
    }

    private static File requireFile(File baseDir, String configured, String key) throws IOException {
        File resolved;
        if (baseDir != null) {
            resolved = new File(baseDir, configured);
        } else {
            resolved = new File(configured);
        }
        resolved = resolved.getAbsoluteFile();
        if (!resolved.exists()) {
            throw new IOException("No existe archivo en " + key + ": " + resolved.getAbsolutePath());
        }
        return resolved;
    }

    public static final class Builder {
        private Long monitorIntervalSeconds;
        private File processWatchListPath;
        private File networkInterfaceExcludePath;
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

        public Builder monitorIntervalSeconds(long value) {
            this.monitorIntervalSeconds = Long.valueOf(value);
            return this;
        }

        public Builder processWatchListPath(File value) {
            this.processWatchListPath = value;
            return this;
        }

        public Builder networkInterfaceExcludePath(File value) {
            this.networkInterfaceExcludePath = value;
            return this;
        }

        public Builder restPort(int value) {
            this.restPort = Integer.valueOf(value);
            return this;
        }

        public Builder mqttBrokerUri(String value) {
            this.mqttBrokerUri = value;
            return this;
        }

        public Builder mqttTopicTemplate(String value) {
            this.mqttTopicTemplate = value;
            return this;
        }

        public Builder mqttSampleWindow(int value) {
            this.mqttSampleWindow = Integer.valueOf(value);
            return this;
        }

        public Builder mqttPublishEveryHttpUpdates(int value) {
            this.mqttPublishEveryHttpUpdates = Integer.valueOf(value);
            return this;
        }

        public Builder mqttClientId(String value) {
            this.mqttClientId = value;
            return this;
        }

        public Builder mqttUsername(String value) {
            this.mqttUsername = value;
            return this;
        }

        public Builder mqttPassword(String value) {
            this.mqttPassword = value;
            return this;
        }

        public Builder mqttAvailabilityEnabled(boolean value) {
            this.mqttAvailabilityEnabled = Boolean.valueOf(value);
            return this;
        }

        public Builder mqttAvailabilityTopic(String value) {
            this.mqttAvailabilityTopic = value;
            return this;
        }

        public Builder mqttRetainAvailability(boolean value) {
            this.mqttRetainAvailability = Boolean.valueOf(value);
            return this;
        }

        public DaemonConfiguration build() {
            if (monitorIntervalSeconds == null || monitorIntervalSeconds.longValue() <= 0L) {
                throw new IllegalStateException("monitor.interval.seconds invalido");
            }
            if (processWatchListPath == null) {
                throw new IllegalStateException("monitor.process.watchlist requerido");
            }
            if (networkInterfaceExcludePath == null) {
                throw new IllegalStateException("monitor.network.interface.exclude requerido");
            }
            if (restPort == null || restPort.intValue() <= 0 || restPort.intValue() > 65535) {
                throw new IllegalStateException("rest.port invalido");
            }
            if (mqttBrokerUri == null || mqttBrokerUri.trim().length() == 0) {
                throw new IllegalStateException("mqtt.broker.uri requerido");
            }
            if (mqttTopicTemplate == null || mqttTopicTemplate.trim().length() == 0) {
                throw new IllegalStateException("mqtt.topic.template requerido");
            }
            if (mqttSampleWindow == null || mqttSampleWindow.intValue() <= 0) {
                throw new IllegalStateException("mqtt.sample.window invalido");
            }
            if (mqttPublishEveryHttpUpdates == null || mqttPublishEveryHttpUpdates.intValue() <= 0) {
                throw new IllegalStateException("mqtt.publish.every.http.updates invalido");
            }
            if (mqttClientId == null || mqttClientId.trim().length() == 0) {
                throw new IllegalStateException("mqtt.client.id requerido");
            }
            if (mqttUsername == null || mqttUsername.trim().length() == 0) {
                throw new IllegalStateException("mqtt.username requerido");
            }
            if (mqttPassword == null || mqttPassword.trim().length() == 0) {
                throw new IllegalStateException("mqtt.password requerido");
            }
            if (mqttAvailabilityEnabled == null) {
                throw new IllegalStateException("mqtt.availability.enabled requerido");
            }
            if (mqttAvailabilityTopic == null || mqttAvailabilityTopic.trim().length() == 0) {
                throw new IllegalStateException("mqtt.availability.topic requerido");
            }
            if (mqttRetainAvailability == null) {
                throw new IllegalStateException("mqtt.retain.availability requerido");
            }
            return new DaemonConfiguration(this);
        }
    }
}
