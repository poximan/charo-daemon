package com.charodaemon.monitor.model;

public final class NetworkAddressInfo {
    private final String address;
    private final String netmask;

    public NetworkAddressInfo(String address, String netmask) {
        this.address = address;
        this.netmask = netmask;
    }

    public String address() {
        return address;
    }

    public String netmask() {
        return netmask;
    }
}
