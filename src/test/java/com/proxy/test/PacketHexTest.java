package com.proxy.test;

import com.proxy.PacketHex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The hex view is how transform-rule payloads are entered, so parsing has to
 * cope both with the dump this app renders (offset column) and with hex the
 * user simply types in (no offset column).
 */
public class PacketHexTest {

    @Test
    @DisplayName("hand-typed hex is parsed as data, not as an offset column")
    void parsesHandTypedHex() {
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, PacketHex.parseHexDump("0102030405"));
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, PacketHex.parseHexDump("01 02 03 04 05 06"));
        assertArrayEquals(new byte[]{(byte) 0xAA, (byte) 0xBB, (byte) 0xCC}, PacketHex.parseHexDump("AABBCC"));
        assertArrayEquals(new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF},
                PacketHex.parseHexDump("DEAD\nBEEF"));
    }

    @Test
    @DisplayName("a rendered dump round-trips through the parser")
    void roundTripsRenderedDump() {
        byte[] short0 = {1, 2, 3, 4, 5};
        assertArrayEquals(short0, PacketHex.parseHexDump(PacketHex.formatHex(short0)));

        byte[] multiLine = new byte[40]; // spans three dump lines
        for (int i = 0; i < multiLine.length; i++) {
            multiLine[i] = (byte) (i * 7);
        }
        assertArrayEquals(multiLine, PacketHex.parseHexDump(PacketHex.formatHex(multiLine)));
    }

    @Test
    @DisplayName("a dump fragment starting at a later offset still drops its offsets")
    void handlesPastedDumpFragment() {
        byte[] data = new byte[32];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        String secondLine = PacketHex.formatHex(data).split("\n")[1];
        byte[] expected = new byte[16];
        System.arraycopy(data, 16, expected, 0, 16);
        assertArrayEquals(expected, PacketHex.parseHexDump(secondLine));
    }

    @Test
    @DisplayName("empty and odd input degrade gracefully")
    void handlesEdgeCases() {
        assertArrayEquals(new byte[0], PacketHex.parseHexDump(""));
        assertArrayEquals(new byte[0], PacketHex.parseHexDump("\n\n"));
        // A trailing half byte is ignored rather than throwing.
        assertArrayEquals(new byte[]{(byte) 0xAB}, PacketHex.parseHexDump("ABC"));
    }

    @Test
    @DisplayName("compact hex round-trips")
    void roundTripsCompactHex() {
        byte[] data = {0, 15, 16, (byte) 0xFF, (byte) 0x80};
        assertEquals("000F10FF80", PacketHex.toCompactHex(data));
        assertArrayEquals(data, PacketHex.fromCompactHex("000F10FF80"));
        assertArrayEquals(data, PacketHex.fromCompactHex("00 0F 10 FF 80"));
    }

    @Test
    @DisplayName("editor body lays digits out in byte pairs, 16 per line, without offsets")
    void formatsEditorBody() {
        assertEquals("12 31 23 1", PacketHex.formatBody("1231231", 16));
        String full = PacketHex.formatBody("000102030405060708090A0B0C0D0E0F10", 16);
        assertEquals("00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F\n10", full);
        assertEquals("00000000\n00000010", PacketHex.formatOffsets(34, 16));
        assertEquals("00000000", PacketHex.formatOffsets(0, 16));
        assertEquals("00 01 02 03 04 05 06 07", PacketHex.formatColumnHeader(8));
        for (int bpl : PacketHex.BYTES_PER_LINE_CHOICES) {
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < bpl * 3 + 5; i++) digits.append(String.format("%02X", i & 0xFF));
            String body = PacketHex.formatBody(digits.toString(), bpl);
            // positions line up with the laid-out text, and map back to their byte
            for (int n = 0; n < digits.length(); n++) {
                int pos = PacketHex.nibblePosition(n, bpl);
                assertEquals(n, PacketHex.nibbleCountBefore(body, pos));
                assertEquals(n / 2, PacketHex.byteIndexAt(pos, bpl));
            }
            assertEquals(String.format("%08X", bpl * 2), PacketHex.formatOffsets(digits.length(), bpl).split("\n")[2]);
        }
    }

    @Test
    @DisplayName("pasted text is reduced to its hex digits")
    void extractsPastedHex() {
        assertEquals("1231231231232", PacketHex.extractHexDigits("1231231231232"));
        assertEquals("1231AB", PacketHex.extractHexDigits("12 31 ab"));
        assertEquals("1231AB", PacketHex.extractHexDigits("0x12, 0x31, 0xAB"));
        byte[] data = new byte[20];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        assertEquals(PacketHex.toCompactHex(data), PacketHex.extractHexDigits(PacketHex.formatHex(data)));
    }
}
