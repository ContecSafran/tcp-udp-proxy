package com.proxy;

import java.util.Arrays;

/**
 * A transform (intercept) rule: when transform mode is enabled for this rule and
 * an incoming client request exactly matches {@code request} (on the same
 * protocol), the proxy replies with {@code response} instead of forwarding to
 * the target server.
 */
public class TransformRule {

    private String name;
    private final String protocol; // "TCP" or "UDP"
    private byte[] request;
    private byte[] response;
    private volatile boolean enabled;

    public TransformRule(String protocol, byte[] request, byte[] response, boolean enabled) {
        this("", protocol, request, response, enabled);
    }

    public TransformRule(String name, String protocol, byte[] request, byte[] response, boolean enabled) {
        this.name = name != null ? name : "";
        this.protocol = protocol;
        this.request = request != null ? request : new byte[0];
        this.response = response != null ? response : new byte[0];
        this.enabled = enabled;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name != null ? name : "";
    }

    public String getProtocol() {
        return protocol;
    }

    public byte[] getRequest() {
        return request;
    }

    public void setRequest(byte[] request) {
        this.request = request != null ? request : new byte[0];
    }

    public byte[] getResponse() {
        return response;
    }

    public void setResponse(byte[] response) {
        this.response = response != null ? response : new byte[0];
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** True if this rule is active and its request matches the given data for the protocol. */
    public boolean matches(String protocol, byte[] data) {
        return enabled && this.protocol.equals(protocol) && Arrays.equals(request, data);
    }

    @Override
    public String toString() {
        String summary = String.format("[%s] req %dB → resp %dB", protocol, request.length, response.length);
        return name.isEmpty() ? summary : name + "  " + summary;
    }
}
