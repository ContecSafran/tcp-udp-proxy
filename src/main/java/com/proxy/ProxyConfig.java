package com.proxy;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Holds the persistable proxy settings (ports/target plus transform rules) and
 * saves/loads them to a {@code proxy-config.properties} file located in the same
 * folder as the running jar (falling back to the working directory).
 */
public class ProxyConfig {

    private static final String FILE_NAME = "proxy-config.properties";

    public String localPort = "25000";
    public String targetIp = "127.0.0.1";
    public String targetPort = "25001";
    public final List<TransformRule> rules = new ArrayList<>();

    /** Resolves the config file next to the running jar (or the working dir). */
    public static File configFile() {
        File dir;
        try {
            File location = new File(ProxyConfig.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            dir = location.isDirectory() ? location : location.getParentFile();
        } catch (URISyntaxException | RuntimeException e) {
            dir = new File(".").getAbsoluteFile();
        }
        if (dir == null) {
            dir = new File(".").getAbsoluteFile();
        }
        return new File(dir, FILE_NAME);
    }

    public void save() throws IOException {
        Properties props = new Properties();
        props.setProperty("localPort", localPort);
        props.setProperty("targetIp", targetIp);
        props.setProperty("targetPort", targetPort);
        props.setProperty("ruleCount", String.valueOf(rules.size()));
        for (int i = 0; i < rules.size(); i++) {
            TransformRule rule = rules.get(i);
            String prefix = "rule." + i + ".";
            props.setProperty(prefix + "name", rule.getName());
            props.setProperty(prefix + "protocol", rule.getProtocol());
            props.setProperty(prefix + "enabled", String.valueOf(rule.isEnabled()));
            props.setProperty(prefix + "request", PacketHex.toCompactHex(rule.getRequest()));
            props.setProperty(prefix + "response", PacketHex.toCompactHex(rule.getResponse()));
        }
        try (FileOutputStream out = new FileOutputStream(configFile())) {
            props.store(out, "TCP/UDP Proxy configuration");
        }
    }

    /** Loads the config file, or returns defaults if it does not exist. */
    public static ProxyConfig load() throws IOException {
        ProxyConfig config = new ProxyConfig();
        File file = configFile();
        if (!file.exists()) {
            return config;
        }
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(file)) {
            props.load(in);
        }
        config.localPort = props.getProperty("localPort", config.localPort);
        config.targetIp = props.getProperty("targetIp", config.targetIp);
        config.targetPort = props.getProperty("targetPort", config.targetPort);

        int count = parseInt(props.getProperty("ruleCount"), 0);
        for (int i = 0; i < count; i++) {
            String prefix = "rule." + i + ".";
            String protocol = props.getProperty(prefix + "protocol", "TCP");
            String name = props.getProperty(prefix + "name", "");
            boolean enabled = Boolean.parseBoolean(props.getProperty(prefix + "enabled", "false"));
            byte[] request = PacketHex.fromCompactHex(props.getProperty(prefix + "request", ""));
            byte[] response = PacketHex.fromCompactHex(props.getProperty(prefix + "response", ""));
            config.rules.add(new TransformRule(name, protocol, request, response, enabled));
        }
        return config;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
