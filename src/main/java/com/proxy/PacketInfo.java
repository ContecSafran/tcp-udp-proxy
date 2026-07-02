package com.proxy;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Represents a single captured packet (TCP segment payload or UDP datagram)
 * forwarded by the proxy, for display in the packet inspector UI.
 *
 * The byte payload is mutable so that packets shown in the transform list can be
 * edited via the hex/ASCII views before being used as a transform response.
 */
public class PacketInfo {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final LocalDateTime time;
    private final String protocol;
    private final String direction;
    private final String remoteAddress;
    private byte[] data;

    public PacketInfo(String protocol, String direction, String remoteAddress, byte[] data) {
        this.time = LocalDateTime.now();
        this.protocol = protocol;
        this.direction = direction;
        this.remoteAddress = remoteAddress;
        this.data = data;
    }

    public String getProtocol() {
        return protocol;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data != null ? data : new byte[0];
    }

    @Override
    public String toString() {
        return String.format("[%s] [%s] %s (%s) - %d bytes",
                time.format(TIME_FORMAT), protocol, direction, remoteAddress, data.length);
    }
}
