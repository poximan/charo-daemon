package com.charodaemon.mqtt;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class MqttPublisherConfig {
    private final String brokerUri;
    private final String clientId;
    private final String topic;
    private final int sampleWindow;
    private final Duration pollingInterval;
    private final String username;
    private final char[] password;

    private MqttPublisherConfig(Builder builder) {
        this.brokerUri = builder.brokerUri;
        this.clientId = builder.clientId != null ? builder.clientId : "charo-daemon-mqtt";
        this.topic = builder.topic;
        this.sampleWindow = builder.sampleWindow;
        this.pollingInterval = builder.pollingInterval;
        this.username = builder.username;
        this.password = builder.password != null ? builder.password.clone() : null;
    }

    public String brokerUri() {
        return brokerUri;
    }

    public String clientId() {
        return clientId;
    }

    public String topic() {
        return topic;
    }

    public int sampleWindow() {
        return sampleWindow;
    }

    public Duration pollingInterval() {
        return pollingInterval;
    }

    public Optional<String> username() {
        return Optional.ofNullable(username);
    }

    public Optional<char[]> password() {
        return password == null ? Optional.empty() : Optional.of(password.clone());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String brokerUri = "tcp://localhost:1883";
        private String clientId;
        private String topic = "charodaemon/metrics";
        private int sampleWindow = 5;
        private Duration pollingInterval = Duration.ofSeconds(20);
        private String username;
        private char[] password;

        private Builder() {
        }

        public Builder brokerUri(String brokerUri) {
            this.brokerUri = Objects.requireNonNull(brokerUri, "brokerUri");
            return this;
        }

        public Builder clientId(String clientId) {
            this.clientId = clientId;
            return this;
        }

        public Builder topic(String topic) {
            this.topic = Objects.requireNonNull(topic, "topic");
            return this;
        }

        public Builder sampleWindow(int sampleWindow) {
            if (sampleWindow <= 0) {
                throw new IllegalArgumentException("sampleWindow must be positive");
            }
            this.sampleWindow = sampleWindow;
            return this;
        }

        public Builder pollingInterval(Duration pollingInterval) {
            this.pollingInterval = Objects.requireNonNull(pollingInterval, "pollingInterval");
            return this;
        }

        public Builder username(String username) {
            this.username = Objects.requireNonNull(username, "username");
            return this;
        }

        public Builder password(String password) {
            Objects.requireNonNull(password, "password");
            this.password = password.toCharArray();
            return this;
        }

        public Builder password(char[] password) {
            this.password = Objects.requireNonNull(password, "password").clone();
            return this;
        }

        public MqttPublisherConfig build() {
            return new MqttPublisherConfig(this);
        }
    }
}
