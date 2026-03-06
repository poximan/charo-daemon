package com.charodaemon.monitor.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class MonitorSettings {
    private final Duration samplingInterval;
    private final Path processWatchListPath;
    private final List<String> initialProcessWatchList;
    private final Path networkInterfaceExcludePath;
    private final List<String> initialNetworkInterfaceExcludeList;

    private MonitorSettings(Builder builder) {
        this.samplingInterval = builder.samplingInterval;
        this.processWatchListPath = builder.processWatchListPath;
        this.initialProcessWatchList = builder.initialProcessWatchList == null
                ? Collections.emptyList()
                : List.copyOf(builder.initialProcessWatchList);
        this.networkInterfaceExcludePath = builder.networkInterfaceExcludePath;
        this.initialNetworkInterfaceExcludeList = builder.initialNetworkInterfaceExcludeList == null
                ? Collections.emptyList()
                : List.copyOf(builder.initialNetworkInterfaceExcludeList);
    }

    public Duration samplingInterval() {
        return samplingInterval;
    }

    public Path processWatchListPath() {
        return processWatchListPath;
    }

    public List<String> initialProcessWatchList() {
        return initialProcessWatchList;
    }

    public Path networkInterfaceExcludePath() {
        return networkInterfaceExcludePath;
    }

    public List<String> initialNetworkInterfaceExcludeList() {
        return initialNetworkInterfaceExcludeList;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Duration samplingInterval;
        private Path processWatchListPath;
        private List<String> initialProcessWatchList;
        private Path networkInterfaceExcludePath;
        private List<String> initialNetworkInterfaceExcludeList;

        private Builder() {
        }

        public Builder samplingInterval(Duration samplingInterval) {
            this.samplingInterval = Objects.requireNonNull(samplingInterval, "samplingInterval");
            return this;
        }

        public Builder processWatchListPath(Path processWatchListPath) {
            this.processWatchListPath = processWatchListPath;
            return this;
        }

        public Builder initialProcessWatchList(List<String> initialProcessWatchList) {
            this.initialProcessWatchList = initialProcessWatchList;
            return this;
        }

        public Builder networkInterfaceExcludePath(Path networkInterfaceExcludePath) {
            this.networkInterfaceExcludePath = networkInterfaceExcludePath;
            return this;
        }

        public Builder initialNetworkInterfaceExcludeList(List<String> initialNetworkInterfaceExcludeList) {
            this.initialNetworkInterfaceExcludeList = initialNetworkInterfaceExcludeList;
            return this;
        }

        public MonitorSettings build() {
            if (samplingInterval == null) {
                throw new IllegalStateException("samplingInterval is required");
            }
            return new MonitorSettings(this);
        }
    }
}
