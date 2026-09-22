package com.proxy;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * A named "project": the proxy connection settings (local port / target IP /
 * target port) together with the transform rules. Serialized as a single JSON
 * document so it can be edited or pasted as text, and stored per project name.
 */
public class ProxyConfig {

    private static final String LEGACY_FILE_NAME = "proxy-config.properties";

    public String localPort = "25000";
    public String targetIp = "127.0.0.1";
    public String targetPort = "25001";
    public final List<TransformRule> rules = new ArrayList<>();

    /** Directory next to the running jar (or the working dir) for config/projects. */
    public static File baseDir() {
        File dir;
        try {
            File location = new File(ProxyConfig.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            dir = location.isDirectory() ? location : location.getParentFile();
        } catch (URISyntaxException | RuntimeException e) {
            dir = new File(".").getAbsoluteFile();
        }
        return dir != null ? dir : new File(".").getAbsoluteFile();
    }

    // --------------------------------------------------------------- JSON form

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"localPort\": ").append(Json.quote(localPort)).append(",\n");
        sb.append("  \"targetIp\": ").append(Json.quote(targetIp)).append(",\n");
        sb.append("  \"targetPort\": ").append(Json.quote(targetPort)).append(",\n");
        sb.append("  \"rules\": ");
        // Embed the rules array, indented one level.
        String[] lines = TransformJson.toJson(rules).stripTrailing().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i == 0) {
                sb.append(lines[i]).append('\n');
            } else {
                sb.append("  ").append(lines[i]).append('\n');
            }
        }
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Parses a project JSON document. Accepts either a full object (with ports
     * and rules) or a bare rules array (ports keep their defaults), so pasted
     * rule-only JSON stays compatible.
     */
    public static ProxyConfig fromJson(String text) {
        Object root = Json.parse(text);
        ProxyConfig config = new ProxyConfig();
        if (root instanceof List) {
            config.rules.addAll(TransformJson.rulesFromArray((List<?>) root));
            return config;
        }
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("최상위 구조는 객체({ ... }) 또는 규칙 배열([ ... ])이어야 합니다.");
        }
        Map<?, ?> obj = (Map<?, ?>) root;
        config.localPort = asString(obj.get("localPort"), config.localPort);
        config.targetIp = asString(obj.get("targetIp"), config.targetIp);
        config.targetPort = asString(obj.get("targetPort"), config.targetPort);
        Object rules = obj.get("rules");
        if (rules instanceof List) {
            config.rules.addAll(TransformJson.rulesFromArray((List<?>) rules));
        } else if (rules != null) {
            throw new IllegalArgumentException("\"rules\"는 배열이어야 합니다.");
        }
        return config;
    }

    private static String asString(Object value, String fallback) {
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof Double) {
            double d = (Double) value;
            return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
        return fallback;
    }

    // --------------------------------------------------------- legacy (migration)

    /** Path of the pre-JSON properties file, used only to migrate old installs. */
    public static File legacyFile() {
        return new File(baseDir(), LEGACY_FILE_NAME);
    }

    /** Loads the legacy properties config if present, else null. */
    public static ProxyConfig loadLegacy() throws IOException {
        File file = legacyFile();
        if (!file.exists()) {
            return null;
        }
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(file)) {
            props.load(in);
        }
        ProxyConfig config = new ProxyConfig();
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
