package com.charodaemon.mqtt;

public final class MqttPublisherConfig {
    private final String brokerUri;
    private final String clientId;
    private final String topicTemplate;
    private final int sampleWindow;
    private final int publishEveryHttpUpdates;
    private final long pollingIntervalSeconds;
    private final String username;
    private final char[] password;
    private final boolean availabilityEnabled;
    private final String availabilityTopic;
    private final boolean retainAvailability;

    private MqttPublisherConfig(Builder builder) {
        this.brokerUri = builder.brokerUri;
        this.clientId = builder.clientId;
        this.topicTemplate = builder.topicTemplate;
        this.sampleWindow = builder.sampleWindow.intValue();
        this.publishEveryHttpUpdates = builder.publishEveryHttpUpdates.intValue();
        this.pollingIntervalSeconds = builder.pollingIntervalSeconds.longValue();
        this.username = builder.username;
        this.password = builder.password == null ? null : (char[]) builder.password.clone();
        this.availabilityEnabled = builder.availabilityEnabled.booleanValue();
        this.availabilityTopic = builder.availabilityTopic;
        this.retainAvailability = builder.retainAvailability.booleanValue();
    }

    public String brokerUri() {
        return brokerUri;
    }

    public String clientId() {
        return clientId;
    }

    public String topicTemplate() {
        return topicTemplate;
    }

    public int sampleWindow() {
        return sampleWindow;
    }

    public int publishEveryHttpUpdates() {
        return publishEveryHttpUpdates;
    }

    public long pollingIntervalSeconds() {
        return pollingIntervalSeconds;
    }

    public String username() {
        return username;
    }

    public char[] password() {
        return password == null ? null : (char[]) password.clone();
    }

    public boolean availabilityEnabled() {
        return availabilityEnabled;
    }

    public String availabilityTopic() {
        return availabilityTopic;
    }

    public boolean retainAvailability() {
        return retainAvailability;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String brokerUri;
        private String clientId;
        private String topicTemplate;
        private Integer sampleWindow;
        private Integer publishEveryHttpUpdates;
        private Long pollingIntervalSeconds;
        private String username;
        private char[] password;
        private Boolean availabilityEnabled;
        private String availabilityTopic;
        private Boolean retainAvailability;

        private Builder() {
        }

        public Builder brokerUri(String brokerUri) {
            this.brokerUri = brokerUri;
            return this;
        }

        public Builder clientId(String clientId) {
            this.clientId = clientId;
            return this;
        }

        public Builder topicTemplate(String topicTemplate) {
            this.topicTemplate = topicTemplate;
            return this;
        }

        public Builder sampleWindow(int sampleWindow) {
            this.sampleWindow = Integer.valueOf(sampleWindow);
            return this;
        }

        public Builder publishEveryHttpUpdates(int publishEveryHttpUpdates) {
            this.publishEveryHttpUpdates = Integer.valueOf(publishEveryHttpUpdates);
            return this;
        }

        public Builder pollingIntervalSeconds(long pollingIntervalSeconds) {
            this.pollingIntervalSeconds = Long.valueOf(pollingIntervalSeconds);
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password == null ? null : password.toCharArray();
            return this;
        }

        public Builder password(char[] password) {
            this.password = password == null ? null : (char[]) password.clone();
            return this;
        }

        public Builder availabilityEnabled(boolean availabilityEnabled) {
            this.availabilityEnabled = Boolean.valueOf(availabilityEnabled);
            return this;
        }

        public Builder availabilityTopic(String availabilityTopic) {
            this.availabilityTopic = availabilityTopic;
            return this;
        }

        public Builder retainAvailability(boolean retainAvailability) {
            this.retainAvailability = Boolean.valueOf(retainAvailability);
            return this;
        }

        public MqttPublisherConfig build() {
            if (brokerUri == null || brokerUri.trim().length() == 0) {
                throw new IllegalArgumentException("mqtt.broker.uri requerido");
            }
            if (clientId == null || clientId.trim().length() == 0) {
                throw new IllegalArgumentException("mqtt.client.id requerido");
            }
            if (topicTemplate == null || topicTemplate.trim().length() == 0) {
                throw new IllegalArgumentException("mqtt.topic.template requerido");
            }
            if (sampleWindow == null || sampleWindow.intValue() <= 0) {
                throw new IllegalArgumentException("mqtt.sample.window invalido");
            }
            if (publishEveryHttpUpdates == null || publishEveryHttpUpdates.intValue() <= 0) {
                throw new IllegalArgumentException("mqtt.publish.every.http.updates invalido");
            }
            if (pollingIntervalSeconds == null || pollingIntervalSeconds.longValue() <= 0L) {
                throw new IllegalArgumentException("monitor.interval.seconds invalido");
            }
            if (username == null || username.trim().length() == 0) {
                throw new IllegalArgumentException("mqtt.username requerido");
            }
            if (password == null || password.length == 0) {
                throw new IllegalArgumentException("mqtt.password requerido");
            }
            if (availabilityEnabled == null) {
                throw new IllegalArgumentException("mqtt.availability.enabled requerido");
            }
            if (availabilityTopic == null || availabilityTopic.trim().length() == 0) {
                throw new IllegalArgumentException("mqtt.availability.topic requerido");
            }
            if (retainAvailability == null) {
                throw new IllegalArgumentException("mqtt.retain.availability requerido");
            }
            return new MqttPublisherConfig(this);
        }
    }
}
