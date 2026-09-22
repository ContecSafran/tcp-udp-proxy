package com.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts the transform-rule list to and from a human-editable JSON document.
 * Byte payloads are represented as hex strings.
 */
public final class TransformJson {

    private TransformJson() {
    }

    public static String toJson(List<TransformRule> rules) {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < rules.size(); i++) {
            TransformRule rule = rules.get(i);
            sb.append("  {\n");
            sb.append("    \"name\": ").append(Json.quote(rule.getName())).append(",\n");
            sb.append("    \"protocol\": ").append(Json.quote(rule.getProtocol())).append(",\n");
            sb.append("    \"enabled\": ").append(rule.isEnabled()).append(",\n");
            sb.append("    \"request\": ").append(Json.quote(PacketHex.toCompactHex(rule.getRequest()))).append(",\n");
            sb.append("    \"response\": ").append(Json.quote(PacketHex.toCompactHex(rule.getResponse()))).append("\n");
            sb.append("  }").append(i < rules.size() - 1 ? "," : "").append("\n");
        }
        sb.append("]\n");
        return sb.toString();
    }

    /** Parses a JSON rules array. Throws IllegalArgumentException on malformed input. */
    public static List<TransformRule> fromJson(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof List)) {
            throw new IllegalArgumentException("최상위 구조는 배열([ ... ])이어야 합니다.");
        }
        return rulesFromArray((List<?>) root);
    }

    /** Builds rules from an already-parsed JSON array of rule objects. */
    public static List<TransformRule> rulesFromArray(List<?> array) {
        List<TransformRule> rules = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            Object element = array.get(i);
            if (!(element instanceof Map)) {
                throw new IllegalArgumentException((i + 1) + "번째 항목이 객체({ ... })가 아닙니다.");
            }
            Map<?, ?> obj = (Map<?, ?>) element;
            String name = asString(obj.get("name"), "");
            String protocol = asString(obj.get("protocol"), "TCP").toUpperCase();
            if (!protocol.equals("TCP") && !protocol.equals("UDP")) {
                throw new IllegalArgumentException((i + 1) + "번째 항목의 protocol은 \"TCP\" 또는 \"UDP\"여야 합니다.");
            }
            boolean enabled = Boolean.TRUE.equals(obj.get("enabled"));
            byte[] request = parseHex(obj.get("request"), i, "request");
            byte[] response = parseHex(obj.get("response"), i, "response");
            rules.add(new TransformRule(name, protocol, request, response, enabled));
        }
        return rules;
    }

    private static String asString(Object value, String fallback) {
        return value instanceof String ? (String) value : fallback;
    }

    private static byte[] parseHex(Object value, int index, String field) {
        String hex = value instanceof String ? (String) value : "";
        try {
            return PacketHex.fromCompactHex(hex);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException((index + 1) + "번째 항목의 " + field + " hex 문자열이 잘못되었습니다.");
        }
    }
}
