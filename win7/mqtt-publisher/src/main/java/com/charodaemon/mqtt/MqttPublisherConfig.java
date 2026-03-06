package com.charodaemon.mqtt;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class MqttPublisherConfig {
    private final String brokerUri;
    private final String clientId;
    private final String topicTemplate; // e.g. "charodaemon/host/{clientId}/metrics" (obligatorio)
    private final int sampleWindow;
    private final int publishEveryHttpUpdates;
    private final Duration pollingInterval;
    private final String username;
    private final char[] password;
    private final boolean availabilityEnabled;
    private final String availabilityTopic; // e.g. "charodaemon/host/{clientId}/status"
    private final boolean retainAvailability;

    private MqttPublisherConfig(Builder builder) {
        this.brokerUri = builder.brokerUri;
        this.clientId = builder.clientId != null ? builder.clientId : "charo-daemon-mqtt";
        this.topicTemplate = builder.topicTemplate;
        this.sampleWindow = builder.sampleWindow;
        this.publishEveryHttpUpdates = builder.publishEveryHttpUpdates;
        this.pollingInterval = builder.pollingInterval;
        this.username = builder.username;
        this.password = builder.password != null ? builder.password.clone() : null;
        this.availabilityEnabled = builder.availabilityEnabled;
        this.availabilityTopic = builder.availabilityTopic;
        this.retainAvailability = builder.retainAvailability;
    }

    public String brokerUri() {
        return brokerUri;
    }

    public String clientId() {
        return clientId;
    }

    public String topicTemplate() { return topicTemplate; }

    public int sampleWindow() {
        return sampleWindow;
    }

    public int publishEveryHttpUpdates() {
        return publishEveryHttpUpdates;
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

    public boolean availabilityEnabled() { return availabilityEnabled; }
    public Optional<String> availabilityTopic() { return Optional.ofNullable(availabilityTopic); }
    public boolean retainAvailability() { return retainAvailability; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String brokerUri;
        private String clientId;
        private String topicTemplate; // obligatorio, sin default
        private int sampleWindow; // >0 requerido
        private int publishEveryHttpUpdates; // >0 requerido
        private Duration pollingInterval; // requerido
        private String username;
        private char[] password;
        private Boolean availabilityEnabled; // obligatorio
        private String availabilityTopic; // obligatorio
        private Boolean retainAvailability; // obligatorio

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

        public Builder topicTemplate(String topicTemplate) {
            this.topicTemplate = Objects.requireNonNull(topicTemplate, "topicTemplate");
            return this;
        }

        public Builder sampleWindow(int sampleWindow) {
            if (sampleWindow <= 0) {
                throw new IllegalArgumentException("sampleWindow must be positive");
            }
            this.sampleWindow = sampleWindow;
            return this;
        }

        public Builder publishEveryHttpUpdates(int publishEveryHttpUpdates) {
            if (publishEveryHttpUpdates <= 0) {
                throw new IllegalArgumentException("publishEveryHttpUpdates must be positive");
            }
            this.publishEveryHttpUpdates = publishEveryHttpUpdates;
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

        public Builder availabilityEnabled(boolean availabilityEnabled) {
            this.availabilityEnabled = availabilityEnabled;
            return this;
        }

        public Builder availabilityTopic(String availabilityTopic) {
            this.availabilityTopic = Objects.requireNonNull(availabilityTopic, "availabilityTopic");
            return this;
        }

        public Builder retainAvailability(boolean retainAvailability) {
            this.retainAvailability = retainAvailability;
            return this;
        }

        public MqttPublisherConfig build() {
            if (this.brokerUri == null || this.brokerUri.isBlank()) throw new IllegalArgumentException("brokerUri requerido");
            if (this.clientId == null || this.clientId.isBlank()) throw new IllegalArgumentException("clientId requerido");
            if (this.topicTemplate == null || this.topicTemplate.isBlank()) throw new IllegalArgumentException("topicTemplate es obligatorio");
            if (this.sampleWindow <= 0) throw new IllegalArgumentException("sampleWindow debe ser > 0");
            if (this.publishEveryHttpUpdates <= 0) throw new IllegalArgumentException("publishEveryHttpUpdates debe ser > 0");
            if (this.pollingInterval == null || this.pollingInterval.isNegative() || this.pollingInterval.isZero()) throw new IllegalArgumentException("pollingInterval invalido");
            if (this.username == null) throw new IllegalArgumentException("username requerido");
            if (this.password == null) throw new IllegalArgumentException("password requerido");
            if (this.availabilityEnabled == null) throw new IllegalArgumentException("availabilityEnabled requerido");
            if (this.availabilityTopic == null || this.availabilityTopic.isBlank()) throw new IllegalArgumentException("availabilityTopic requerido");
            if (this.retainAvailability == null) throw new IllegalArgumentException("retainAvailability requerido");
            return new MqttPublisherConfig(this);
        }
    }
}
