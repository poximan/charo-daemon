package com.charodaemon.monitor.model;

import java.util.List;

public record NetworkInterfaceInfo(
        String name,
        String displayName,
        String path,
        String macAddress,
        boolean up,
        boolean virtual,
        List<NetworkAddressInfo> addresses
) {
}
