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

    // ---- Editor body layout: the dump without its offset column ----
    // Each line holds `bpl` bytes as "XX XX ... XX" (no trailing spaces), lines
    // joined by '\n', so a full line plus its '\n' is exactly bpl * 3 chars. The
    // offsets are drawn in a separate, non-editable gutter so they can never be
    // typed over.

    /** Byte-per-line choices offered by the editor. */
    public static final int[] BYTES_PER_LINE_CHOICES = {8, 16, 24, 32, 48, 64};

    /** Lays out a string of hex digits (nibbles) as editor body text, {@code bpl} bytes per line. */
    public static String formatBody(String nibbles, int bpl) {
        StringBuilder sb = new StringBuilder(nibbles.length() * 2);
        for (int n = 0; n < nibbles.length(); n += 2) {
            int b = n / 2;
            if (b > 0) sb.append(b % bpl == 0 ? '\n' : ' ');
            sb.append(Character.toUpperCase(nibbles.charAt(n)));
            if (n + 1 < nibbles.length()) sb.append(Character.toUpperCase(nibbles.charAt(n + 1)));
        }
        return sb.toString();
    }

    /** Offset gutter text matching {@link #formatBody}: one "%08X" per body line. */
    public static String formatOffsets(int nibbleCount, int bpl) {
        int lines = Math.max(1, (nibbleCount + bpl * 2 - 1) / (bpl * 2));
        StringBuilder sb = new StringBuilder(lines * 9);
        for (int i = 0; i < lines; i++) {
            if (i > 0) sb.append('\n');
            sb.append(String.format("%08X", (long) i * bpl));
        }
        return sb.toString();
    }

    /** Column header over the body: "00 01 02 ..." up to {@code bpl - 1}. */
    public static String formatColumnHeader(int bpl) {
        StringBuilder sb = new StringBuilder(bpl * 3);
        for (int i = 0; i < bpl; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", i));
        }
        return sb.toString();
    }

    /** Text position of nibble {@code n} in {@link #formatBody} output (may exceed the text length at the end). */
    public static int nibblePosition(int n, int bpl) {
        int b = n / 2;
        return (b / bpl) * bpl * 3 + (b % bpl) * 3 + n % 2;
    }

    /** Index of the byte under text position {@code pos} in body text. */
    public static int byteIndexAt(int pos, int bpl) {
        if (pos < 0) return 0;
        int stride = bpl * 3;
        return (pos / stride) * bpl + Math.min((pos % stride) / 3, bpl - 1);
    }

    /** Number of hex digits in {@code text} before position {@code pos}. */
    public static int nibbleCountBefore(String text, int pos) {
        int count = 0;
        int end = Math.min(pos, text.length());
        for (int i = 0; i < end; i++) {
            if (Character.digit(text.charAt(i), 16) >= 0) count++;
        }
        return count;
    }

    /**
     * Pulls the hex digits out of typed or pasted text, uppercased. Understands a
     * pasted {@link #formatHex} dump (offset column dropped) and "0x" prefixes, so
     * "1231AB", "12 31 AB", "0x12, 0x31" and a copied dump all work.
     */
    public static String extractHexDigits(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] lines = text.replace("\r", "").split("\n", -1);
        boolean dump = lines.length > 0 && looksLikeDump(lines);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String body = dump && line.length() > OFFSET_WIDTH ? line.substring(OFFSET_WIDTH) : line;
            body = body.replaceAll("(?<![0-9A-Fa-f])0[xX](?=[0-9A-Fa-f])", "");
            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                if (Character.digit(c, 16) >= 0) sb.append(Character.toUpperCase(c));
            }
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
     * Parses an edited hex dump back into bytes. Text laid out by
     * {@link #formatHex} has its offset column dropped; text typed by hand
     * (plain hex, with or without spaces or line breaks) is taken as-is, so a
     * rule payload can simply be typed in as "01 02 03".
     */
    public static byte[] parseHexDump(String text) {
        String[] lines = text.split("\n", -1);
        boolean dump = looksLikeDump(lines);

        StringBuilder hex = new StringBuilder();
        for (String line : lines) {
            String body = dump && line.length() > OFFSET_WIDTH ? line.substring(OFFSET_WIDTH) : line;
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

    /**
     * True if every non-empty line carries a {@link #formatHex} offset column:
     * eight hex digits, two spaces, and offsets that step by {@link #BYTES_PER_LINE}.
     * Hand-typed hex fails this test, so its leading digits are kept as data.
     */
    private static boolean looksLikeDump(String[] lines) {
        long expected = -1;
        boolean any = false;
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            if (line.length() < OFFSET_WIDTH + 2
                    || line.charAt(8) != ' ' || line.charAt(9) != ' ') {
                return false;
            }
            long offset;
            try {
                offset = Long.parseLong(line.substring(0, 8), 16);
            } catch (NumberFormatException e) {
                return false;
            }
            if (expected < 0) {
                if (offset % BYTES_PER_LINE != 0) return false; // first line of a dump is aligned
            } else if (offset != expected) {
                return false;
            }
            expected = offset + BYTES_PER_LINE;
            any = true;
        }
        return any;
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
