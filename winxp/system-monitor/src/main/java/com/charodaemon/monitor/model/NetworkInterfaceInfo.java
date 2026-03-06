package com.charodaemon.monitor.model;

import java.util.List;

public final class NetworkInterfaceInfo {
    private final String name;
    private final String displayName;
    private final String path;
    private final String macAddress;
    private final boolean up;
    private final boolean virtual;
    private final List<NetworkAddressInfo> addresses;

    public NetworkInterfaceInfo(
            String name,
            String displayName,
            String path,
            String macAddress,
            boolean up,
            boolean virtual,
            List<NetworkAddressInfo> addresses
    ) {
        this.name = name;
        this.displayName = displayName;
        this.path = path;
        this.macAddress = macAddress;
        this.up = up;
        this.virtual = virtual;
        this.addresses = addresses;
    }

    public String name() {
        return name;
    }

    public String displayName() {
        return displayName;
    }

    public String path() {
        return path;
    }

    public String macAddress() {
        return macAddress;
    }

    public boolean up() {
        return up;
    }

    public boolean virtual() {
        return virtual;
    }

    public List<NetworkAddressInfo> addresses() {
        return addresses;
    }
}
