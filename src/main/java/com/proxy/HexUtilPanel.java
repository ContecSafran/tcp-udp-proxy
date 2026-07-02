package com.proxy;

import javax.swing.*;
import java.awt.*;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Utility bar attached to the hex view. A combo box switches between two tools:
 * a data converter that decodes the bytes at the caret position as a selected
 * numeric type (with endianness), and a checksum calculator (Modular Sum / XOR)
 * over the selected byte range.
 *
 * The caret-to-byte-index math must match the layout produced by
 * ProxyApp.formatHex(): "%08X  " offset column, 16 bytes per line as "XX ",
 * with one extra space after the 8th byte.
 */
public class HexUtilPanel extends JPanel {

    private static final int BYTES_PER_LINE = 16;
    private static final int OFFSET_WIDTH = 10;
    // offset column + 16 * "XX " + mid-line gap + '\n'
    private static final int LINE_STRIDE = OFFSET_WIDTH + BYTES_PER_LINE * 3 + 1 + 1;

    private final JTextArea hexArea;
    private byte[] data = new byte[0];

    // Data converter tool
    private final JComboBox<String> endianCombo = new JComboBox<>(new String[]{
            "Big Endian", "Little Endian"});
    private final JComboBox<String> dataTypeCombo = new JComboBox<>(new String[]{
            "2진수 (8비트)", "Int8", "UInt8", "Int16", "UInt16", "Int24", "UInt24",
            "Int32", "UInt32", "Int64", "UInt64", "Single (float32)", "Double (float64)"});
    private final JTextField valueField = new JTextField(14);

    // Checksum tool
    private final JComboBox<String> checksumMethodCombo = new JComboBox<>(new String[]{
            "Modular Sum", "XOR"});
    private final JTextField checksumField = new JTextField(4);
    private final JLabel checksumInfoLabel = new JLabel("Hex 뷰에서 범위를 선택하세요.");

    public HexUtilPanel(JTextArea hexArea) {
        super(new FlowLayout(FlowLayout.LEFT, 4, 2));
        this.hexArea = hexArea;

        JComboBox<String> toolSelector = new JComboBox<>(new String[]{"데이터 변환기", "CheckSum 계산기"});
        CardLayout cardLayout = new CardLayout();
        JPanel cards = new JPanel(cardLayout);

        JPanel converterCard = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        converterCard.add(endianCombo);
        converterCard.add(dataTypeCombo);
        converterCard.add(valueField);

        JPanel checksumCard = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        checksumCard.add(checksumMethodCombo);
        checksumCard.add(checksumField);
        checksumCard.add(checksumInfoLabel);

        cards.add(converterCard, "converter");
        cards.add(checksumCard, "checksum");

        add(toolSelector);
        add(cards);

        dataTypeCombo.setSelectedIndex(7); // Int32
        valueField.setEditable(false);
        checksumField.setEditable(false);

        toolSelector.addActionListener(e ->
                cardLayout.show(cards, toolSelector.getSelectedIndex() == 0 ? "converter" : "checksum"));
        endianCombo.addActionListener(e -> refresh());
        dataTypeCombo.addActionListener(e -> refresh());
        checksumMethodCombo.addActionListener(e -> refresh());
        hexArea.addCaretListener(e -> refresh());
    }

    /** Sets the raw bytes backing the hex view; null clears. */
    public void setData(byte[] data) {
        this.data = data != null ? data : new byte[0];
        refresh();
    }

    private void refresh() {
        updateConvertedValue();
        updateChecksum();
    }

    private void updateConvertedValue() {
        if (data.length == 0) {
            valueField.setText("");
            return;
        }
        int byteIndex = byteIndexFromCaret(hexArea.getSelectionStart());
        String type = (String) dataTypeCombo.getSelectedItem();
        int needed = neededBytes(type);
        if (byteIndex < 0 || needed <= 0 || byteIndex + needed > data.length) {
            valueField.setText("");
            return;
        }
        byte[] chunk = Arrays.copyOfRange(data, byteIndex, byteIndex + needed);
        if (endianCombo.getSelectedIndex() == 1) {
            for (int i = 0, j = chunk.length - 1; i < j; i++, j--) {
                byte t = chunk[i];
                chunk[i] = chunk[j];
                chunk[j] = t;
            }
        }
        valueField.setText(decode(chunk, type));
    }

    private void updateChecksum() {
        int selStart = hexArea.getSelectionStart();
        int selEnd = hexArea.getSelectionEnd();
        if (data.length == 0 || selStart == selEnd) {
            checksumField.setText("");
            checksumInfoLabel.setText("Hex 뷰에서 범위를 선택하세요.");
            return;
        }
        int sum = 0;
        int xor = 0;
        int count = 0;
        for (int i = 0; i < data.length; i++) {
            int digitPos = byteDigitPos(i);
            if (digitPos + 2 <= selStart) continue;
            if (digitPos >= selEnd) break;
            int b = data[i] & 0xFF;
            sum += b;
            xor ^= b;
            count++;
        }
        if (count == 0) {
            checksumField.setText("");
            checksumInfoLabel.setText("선택 영역에 데이터가 없습니다.");
            return;
        }
        boolean modular = checksumMethodCombo.getSelectedIndex() == 0;
        checksumField.setText(String.format("%02X", (modular ? sum : xor) & 0xFF));
        checksumInfoLabel.setText(count + "바이트 계산 완료");
    }

    /** Maps a caret position in the hex dump to the index of the byte under it. */
    private int byteIndexFromCaret(int pos) {
        if (pos < 0) return 0;
        int line = pos / LINE_STRIDE;
        int rel = pos % LINE_STRIDE - OFFSET_WIDTH;
        if (rel < 0) rel = 0;
        if (rel >= 8 * 3 + 1) rel -= 1; // absorb the mid-line gap before the 9th byte
        int byteInLine = Math.min(rel / 3, BYTES_PER_LINE - 1);
        return line * BYTES_PER_LINE + byteInLine;
    }

    /** Position of the first hex digit of byte {@code index} in the dump text. */
    private int byteDigitPos(int index) {
        int line = index / BYTES_PER_LINE;
        int i = index % BYTES_PER_LINE;
        return line * LINE_STRIDE + OFFSET_WIDTH + i * 3 + (i >= 8 ? 1 : 0);
    }

    private static int neededBytes(String type) {
        switch (type) {
            case "2진수 (8비트)":
            case "Int8":
            case "UInt8":
                return 1;
            case "Int16":
            case "UInt16":
                return 2;
            case "Int24":
            case "UInt24":
                return 3;
            case "Int32":
            case "UInt32":
            case "Single (float32)":
                return 4;
            case "Int64":
            case "UInt64":
            case "Double (float64)":
                return 8;
            default:
                return 0;
        }
    }

    /** Decodes a big-endian-ordered chunk as the given data type. */
    private static String decode(byte[] chunk, String type) {
        ByteBuffer buf = ByteBuffer.wrap(chunk);
        switch (type) {
            case "2진수 (8비트)":
                return String.format("%8s", Integer.toBinaryString(chunk[0] & 0xFF)).replace(' ', '0');
            case "Int8":
                return String.valueOf(chunk[0]);
            case "UInt8":
                return String.valueOf(chunk[0] & 0xFF);
            case "Int16":
                return String.valueOf(buf.getShort());
            case "UInt16":
                return String.valueOf(buf.getShort() & 0xFFFF);
            case "Int24": {
                int v = ((chunk[0] & 0xFF) << 16) | ((chunk[1] & 0xFF) << 8) | (chunk[2] & 0xFF);
                if ((v & 0x800000) != 0) v |= 0xFF000000;
                return String.valueOf(v);
            }
            case "UInt24":
                return String.valueOf(((chunk[0] & 0xFF) << 16) | ((chunk[1] & 0xFF) << 8) | (chunk[2] & 0xFF));
            case "Int32":
                return String.valueOf(buf.getInt());
            case "UInt32":
                return String.valueOf(buf.getInt() & 0xFFFFFFFFL);
            case "Int64":
                return String.valueOf(buf.getLong());
            case "UInt64":
                return Long.toUnsignedString(buf.getLong());
            case "Single (float32)":
                return String.valueOf(buf.getFloat());
            case "Double (float64)":
                return String.valueOf(buf.getDouble());
            default:
                return "";
        }
    }
}
