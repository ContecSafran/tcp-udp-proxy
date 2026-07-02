package com.proxy;

/**
 * Shared helpers for rendering and parsing the hex/ASCII views. The dump layout
 * ("%08X" offset + two spaces, 16 bytes per line as "XX ", extra space after the
 * 8th byte) is relied on by both the parsers here and {@link HexUtilPanel}.
 */
public final class PacketHex {

    public static final int BYTES_PER_LINE = 16;
    public static final int OFFSET_WIDTH = 10; // 8 hex digits + two spaces

    private PacketHex() {
    }

    public static String formatHex(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int offset = 0; offset < data.length; offset += BYTES_PER_LINE) {
            sb.append(String.format("%08X  ", offset));
            int lineLen = Math.min(BYTES_PER_LINE, data.length - offset);
            for (int i = 0; i < BYTES_PER_LINE; i++) {
                if (i == 8) sb.append(' ');
                if (i < lineLen) {
                    sb.append(String.format("%02X ", data[offset + i]));
                } else {
                    sb.append("   ");
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public static String formatAscii(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder sb = new StringBuilder(data.length);
        for (byte b : data) {
            int v = b & 0xFF;
            sb.append(v >= 32 && v < 127 ? (char) v : '.');
        }
        return sb.toString();
    }

    /**
     * Parses an edited hex dump back into bytes. The leading offset column of
     * each line is dropped, then every remaining hex-digit pair is collected.
     */
    public static byte[] parseHexDump(String text) {
        StringBuilder hex = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String body = line.length() > OFFSET_WIDTH ? line.substring(OFFSET_WIDTH) : "";
            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                if (Character.digit(c, 16) >= 0) {
                    hex.append(c);
                }
            }
        }
        int byteCount = hex.length() / 2;
        byte[] result = new byte[byteCount];
        for (int i = 0; i < byteCount; i++) {
            result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    /** Parses edited ASCII text back into bytes (each char mapped to a single byte). */
    public static byte[] parseAscii(String text) {
        byte[] result = new byte[text.length()];
        for (int i = 0; i < text.length(); i++) {
            result[i] = (byte) (text.charAt(i) & 0xFF);
        }
        return result;
    }

    /** Encodes bytes as a continuous uppercase hex string (for compact config storage). */
    public static String toCompactHex(byte[] data) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    /** Decodes a hex string (whitespace ignored) back into bytes. */
    public static byte[] fromCompactHex(String hex) {
        if (hex == null) return new byte[0];
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if (Character.digit(c, 16) >= 0) clean.append(c);
        }
        int byteCount = clean.length() / 2;
        byte[] result = new byte[byteCount];
        for (int i = 0; i < byteCount; i++) {
            result[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }
}
