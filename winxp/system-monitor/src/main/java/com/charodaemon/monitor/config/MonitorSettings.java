package com.charodaemon.monitor.config;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MonitorSettings {
    private final long samplingIntervalSeconds;
    private final File processWatchListPath;
    private final List<String> initialProcessWatchList;
    private final File networkInterfaceExcludePath;
    private final List<String> initialNetworkInterfaceExcludeList;

    private MonitorSettings(Builder builder) {
        this.samplingIntervalSeconds = builder.samplingIntervalSeconds;
        this.processWatchListPath = builder.processWatchListPath;
        this.initialProcessWatchList = builder.initialProcessWatchList == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(builder.initialProcessWatchList));
        this.networkInterfaceExcludePath = builder.networkInterfaceExcludePath;
        this.initialNetworkInterfaceExcludeList = builder.initialNetworkInterfaceExcludeList == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(builder.initialNetworkInterfaceExcludeList));
    }

    public long samplingIntervalSeconds() {
        return samplingIntervalSeconds;
    }

    public File processWatchListPath() {
        return processWatchListPath;
    }

    public List<String> initialProcessWatchList() {
        return initialProcessWatchList;
    }

    public File networkInterfaceExcludePath() {
        return networkInterfaceExcludePath;
    }

    public List<String> initialNetworkInterfaceExcludeList() {
        return initialNetworkInterfaceExcludeList;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Long samplingIntervalSeconds;
        private File processWatchListPath;
        private List<String> initialProcessWatchList;
        private File networkInterfaceExcludePath;
        private List<String> initialNetworkInterfaceExcludeList;

        private Builder() {
        }

        public Builder samplingIntervalSeconds(long samplingIntervalSeconds) {
            this.samplingIntervalSeconds = Long.valueOf(samplingIntervalSeconds);
            return this;
        }

        public Builder processWatchListPath(File processWatchListPath) {
            this.processWatchListPath = processWatchListPath;
            return this;
        }

        public Builder initialProcessWatchList(List<String> initialProcessWatchList) {
            this.initialProcessWatchList = initialProcessWatchList;
            return this;
        }

        public Builder networkInterfaceExcludePath(File networkInterfaceExcludePath) {
            this.networkInterfaceExcludePath = networkInterfaceExcludePath;
            return this;
        }

        public Builder initialNetworkInterfaceExcludeList(List<String> initialNetworkInterfaceExcludeList) {
            this.initialNetworkInterfaceExcludeList = initialNetworkInterfaceExcludeList;
            return this;
        }

        public MonitorSettings build() {
            if (samplingIntervalSeconds == null || samplingIntervalSeconds.longValue() <= 0L) {
                throw new IllegalStateException("samplingIntervalSeconds es obligatorio y debe ser > 0");
            }
            return new MonitorSettings(this);
        }
    }
}
