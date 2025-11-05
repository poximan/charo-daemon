package com.charodaemon.monitor.model;

import java.util.List;

public record ProcessStatus(
        String processName,
        boolean running,
        List<Long> processIds
) {
}
