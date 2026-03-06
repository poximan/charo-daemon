package com.charodaemon.rest;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class RestServerConfig {
    private final int port;
    private final String instanceId;
    private final boolean scadaEnabled;
    private final Path scadaScriptPath;
    private final Path powershellPath;
    private final Duration scadaTimeout;
    private final Duration scadaPollInterval;
    private final Path configFile;

    private RestServerConfig(Builder builder) {
        this.port = builder.port;
        this.instanceId = builder.instanceId;
        this.scadaEnabled = builder.scadaEnabled != null && builder.scadaEnabled;
        this.scadaScriptPath = builder.scadaScriptPath;
        this.powershellPath = builder.powershellPath;
        this.scadaTimeout = builder.scadaTimeout != null ? builder.scadaTimeout : Duration.ofSeconds(30);
        this.scadaPollInterval = builder.scadaPollInterval != null ? builder.scadaPollInterval : Duration.ofSeconds(30);
        this.configFile = builder.configFile;
    }

    public int port() {
        return port;
    }

    public String instanceId() {
        return instanceId;
    }

    public boolean scadaEnabled() {
        return scadaEnabled && scadaScriptPath != null && powershellPath != null;
    }

    public Optional<Path> scadaScriptPath() {
        return Optional.ofNullable(scadaScriptPath);
    }

    public Optional<Path> powershellPath() {
        return Optional.ofNullable(powershellPath);
    }

    public Duration scadaTimeout() {
        return scadaTimeout;
    }

    public Duration scadaPollInterval() {
        return scadaPollInterval;
    }

    public Path configFile() {
        return configFile;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Integer port;
        private String instanceId;
        private Boolean scadaEnabled;
        private Path scadaScriptPath;
        private Path powershellPath;
        private Duration scadaTimeout;
        private Duration scadaPollInterval;
        private Path configFile;

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

        public Builder scadaEnabled(boolean enabled) {
            this.scadaEnabled = enabled;
            return this;
        }

        public Builder scadaScriptPath(Path scriptPath) {
            this.scadaScriptPath = scriptPath;
            return this;
        }

        public Builder powershellPath(Path path) {
            this.powershellPath = path;
            return this;
        }

        public Builder scadaTimeout(Duration timeout) {
            this.scadaTimeout = timeout;
            return this;
        }

        public Builder scadaPollInterval(Duration interval) {
            this.scadaPollInterval = interval;
            return this;
        }

        public Builder configFile(Path configFile) {
            this.configFile = configFile;
            return this;
        }

        public RestServerConfig build() {
            if (port == null) {
                throw new IllegalStateException("REST port is required");
            }
            if (instanceId == null || instanceId.isBlank()) {
                throw new IllegalStateException("instanceId is required");
            }
            if (Boolean.TRUE.equals(scadaEnabled)) {
                if (scadaScriptPath == null) {
                    throw new IllegalStateException("scada script path requerido cuando scada esta habilitado");
                }
                if (powershellPath == null) {
                    throw new IllegalStateException("ruta de powershell requerida cuando scada esta habilitado");
                }
                if (scadaPollInterval == null) {
                    scadaPollInterval = Duration.ofSeconds(30);
                }
                if (configFile == null) {
                    throw new IllegalStateException("configFile requerido para SCADA");
                }
            }
            return new RestServerConfig(this);
        }
    }
}
