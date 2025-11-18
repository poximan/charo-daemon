package com.charodaemon.rest;

import java.util.Objects;

public final class RestServerConfig {
    private final int port;
    private final String instanceId;

    private RestServerConfig(Builder builder) {
        this.port = builder.port;
        this.instanceId = builder.instanceId;
    }

    public int port() {
        return port;
    }

    public String instanceId() {
        return instanceId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Integer port;
        private String instanceId;

        private Builder() {
        }

        public Builder port(int port) {
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("Port out of range: " + port);
            }
            this.port = port;
            return this;
        }

        public Builder instanceId(String instanceId) {
            if (instanceId == null || instanceId.isBlank()) {
                throw new IllegalArgumentException("instanceId requerido");
            }
            this.instanceId = instanceId;
            return this;
        }

        public RestServerConfig build() {
            if (port == null) {
                throw new IllegalStateException("REST port is required");
            }
            if (instanceId == null || instanceId.isBlank()) {
                throw new IllegalStateException("instanceId is required");
            }
            return new RestServerConfig(this);
        }
    }
}
