package com.charodaemon.rest;

import java.util.Objects;

public final class RestServerConfig {
    private final int port;

    private RestServerConfig(Builder builder) {
        this.port = builder.port;
    }

    public int port() {
        return port;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int port = 8080;

        private Builder() {
        }

        public Builder port(int port) {
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("Port out of range: " + port);
            }
            this.port = port;
            return this;
        }

        public RestServerConfig build() {
            return new RestServerConfig(this);
        }
    }
}
