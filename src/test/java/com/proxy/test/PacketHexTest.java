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
}
