package com.charodaemon.monitor.model;

import java.util.List;

public final class ProcessStatus {
    private final String processName;
    private final boolean running;
    private final List<Long> processIds;

    public ProcessStatus(String processName, boolean running, List<Long> processIds) {
        this.processName = processName;
        this.running = running;
        this.processIds = processIds;
    }

    public String processName() {
        return processName;
    }

    public boolean running() {
        return running;
    }

    public List<Long> processIds() {
        return processIds;
    }
}
