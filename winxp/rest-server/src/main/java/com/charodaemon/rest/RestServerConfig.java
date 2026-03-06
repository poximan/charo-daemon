package com.charodaemon.rest;

public final class RestServerConfig {
    private final int port;
    private final String instanceId;

    private RestServerConfig(Builder builder) {
        this.port = builder.port.intValue();
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
                throw new IllegalArgumentException("Port fuera de rango: " + port);
            }
            this.port = Integer.valueOf(port);
            return this;
        }

        public Builder instanceId(String instanceId) {
            if (instanceId == null || instanceId.trim().length() == 0) {
                throw new IllegalArgumentException("instanceId requerido");
            }
            this.instanceId = instanceId;
            return this;
        }

        public RestServerConfig build() {
            if (port == null) {
                throw new IllegalStateException("REST port requerido");
            }
            if (instanceId == null || instanceId.trim().length() == 0) {
                throw new IllegalStateException("instanceId requerido");
            }
            return new RestServerConfig(this);
        }
    }
}
