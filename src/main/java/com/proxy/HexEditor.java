package com.proxy;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;

/**
 * A reusable editor showing a byte payload as side-by-side hex and ASCII views,
 * kept in sync live. The hex view holds only the byte digits laid out by
 * {@link PacketHex#formatBody}; offsets sit in a non-editable gutter. While
 * editing, every keystroke or paste is reduced to its hex digits and the whole
 * view is re-laid out at once, so spacing and line breaks never need typing.
 */
public class HexEditor extends JPanel {

    private final JTextArea hexArea = new JTextArea();
    private final JTextArea offsetArea = new JTextArea();
    private final JTextArea columnArea = new JTextArea();
    private final JTextArea asciiArea = new JTextArea();
    private final HexUtilPanel hexUtilPanel = new HexUtilPanel(hexArea);
    private final JLabel statusLabel = new JLabel();
    private final JComboBox<Integer> bplCombo = new JComboBox<>();

    private byte[] data = new byte[0];
    private int bytesPerLine = 16;
    /** Hex digits currently shown (may hold a trailing half byte while typing). */
    private String nibbles = "";
    /** Set while this class rewrites a view itself, so filters/listeners stand aside. */
    private boolean syncing;

    public HexEditor(boolean editable) {
        this(editable, false);
    }

    /**
     * @param editable whether the hex/ASCII views can be edited
     * @param vertical true to stack Hex above ASCII, false for side-by-side
     */
    public HexEditor(boolean editable, boolean vertical) {
        super(new BorderLayout());
        setBackground(UiTheme.SURFACE);

        hexArea.setEditable(editable);
        asciiArea.setEditable(editable);
        UiTheme.styleArea(hexArea, UiTheme.HEX_BG, UiTheme.HEX_FG, true);
        UiTheme.styleArea(asciiArea, UiTheme.HEX_BG, UiTheme.HEX_FG, true);
        asciiArea.setLineWrap(true);

        UiTheme.styleArea(offsetArea, UiTheme.HEX_BG, UiTheme.MUTED, true);
        offsetArea.setEditable(false);
        offsetArea.setFocusable(false);
        offsetArea.setColumns(9); // wide enough for the "Offset(h)" corner label above it
        offsetArea.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, UiTheme.BORDER),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        offsetArea.setText(PacketHex.formatOffsets(0, bytesPerLine));

        // Column numbers (00 01 02 ...) above the bytes, scrolled together with them.
        UiTheme.styleArea(columnArea, UiTheme.HEX_BG, UiTheme.ACCENT, true);
        columnArea.setEditable(false);
        columnArea.setFocusable(false);
        columnArea.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UiTheme.BORDER),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        columnArea.setText(PacketHex.formatColumnHeader(bytesPerLine));

        JLabel corner = new JLabel("Offset(h)");
        corner.setFont(UiTheme.MONO);
        corner.setForeground(UiTheme.ACCENT);
        corner.setOpaque(true);
        corner.setBackground(UiTheme.HEX_BG);
        corner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 1, UiTheme.BORDER),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));

        JScrollPane hexScroll = UiTheme.scroll(hexArea);
        hexScroll.setRowHeaderView(offsetArea);
        hexScroll.setColumnHeaderView(columnArea);
        hexScroll.setCorner(ScrollPaneConstants.UPPER_LEFT_CORNER, corner);
        JScrollPane asciiScroll = UiTheme.scroll(asciiArea);
        // Equal small preferred widths so the split honours resizeWeight instead of
        // letting the wide hex dump push the ASCII column to almost nothing.
        hexScroll.setPreferredSize(new Dimension(10, 10));
        asciiScroll.setPreferredSize(new Dimension(10, 10));

        JPanel hexPanel = new JPanel(new BorderLayout());
        hexPanel.setBackground(UiTheme.SURFACE);
        hexPanel.add(hexScroll, BorderLayout.CENTER);

        statusLabel.setFont(UiTheme.BASE);
        statusLabel.setForeground(UiTheme.TEXT);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 2, 0, 0));
        JPanel south = new JPanel(new BorderLayout());
        south.setBackground(UiTheme.SURFACE);
        south.add(statusLabel, BorderLayout.NORTH);
        south.add(hexUtilPanel, BorderLayout.CENTER);
        hexPanel.add(south, BorderLayout.SOUTH);

        for (int choice : PacketHex.BYTES_PER_LINE_CHOICES) bplCombo.addItem(choice);
        bplCombo.setSelectedItem(bytesPerLine);
        bplCombo.setBackground(UiTheme.SURFACE);
        bplCombo.setToolTipText("한 줄에 표시할 바이트 수");
        bplCombo.addActionListener(e -> setBytesPerLine((Integer) bplCombo.getSelectedItem()));
        JLabel bplLabel = new JLabel("한 줄 바이트");
        bplLabel.setForeground(UiTheme.MUTED);
        JPanel bplPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        bplPanel.setOpaque(false);
        bplPanel.add(bplLabel);
        bplPanel.add(bplCombo);

        hexArea.addCaretListener(e -> updateStatus());

        int orientation = vertical ? JSplitPane.VERTICAL_SPLIT : JSplitPane.HORIZONTAL_SPLIT;
        JSplitPane split = new JSplitPane(orientation,
                withTitle("Hex", hexPanel, bplPanel),
                withTitle("ASCII", asciiScroll, null));
        UiTheme.styleSplit(split);
        split.setResizeWeight(0.5);
        // The wide converter bar under the hex view inflates the hex side's
        // preferred size; pin the divider to 50% once the split has a real size.
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            private boolean done;
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                int extent = vertical ? split.getHeight() : split.getWidth();
                if (!done && extent > 0) {
                    done = true;
                    split.setDividerLocation(0.5);
                }
            }
        });
        add(split, BorderLayout.CENTER);

        if (editable) {
            ((javax.swing.text.AbstractDocument) hexArea.getDocument()).setDocumentFilter(new HexFilter());
            hexArea.addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent e) {
                    // A dangling half byte can't be stored; drop it so what is shown is what is saved.
                    if (nibbles.length() % 2 != 0) render();
                }
            });
            asciiArea.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { onAsciiEdited(); }
                @Override public void removeUpdate(DocumentEvent e) { onAsciiEdited(); }
                @Override public void changedUpdate(DocumentEvent e) { }
            });
        }
    }

    private static JComponent withTitle(String title, JComponent content, JComponent tools) {
        JLabel header = new JLabel(title.toUpperCase());
        header.setFont(UiTheme.SECTION);
        header.setForeground(UiTheme.MUTED);
        header.setBorder(BorderFactory.createEmptyBorder(0, 2, 4, 0));

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(header, BorderLayout.WEST);
        if (tools != null) top.add(tools, BorderLayout.EAST);

        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBackground(UiTheme.SURFACE);
        panel.add(top, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    /** Re-lays the hex view with a different number of bytes per line, keeping the caret on the same digit. */
    public void setBytesPerLine(int bpl) {
        if (bpl <= 0 || bpl == bytesPerLine) return;
        int caretNibble = PacketHex.nibbleCountBefore(hexArea.getText(), hexArea.getCaretPosition());
        bytesPerLine = bpl;
        if (!Integer.valueOf(bpl).equals(bplCombo.getSelectedItem())) bplCombo.setSelectedItem(bpl);
        columnArea.setText(PacketHex.formatColumnHeader(bpl));
        hexUtilPanel.setBytesPerLine(bpl);
        String body = PacketHex.formatBody(nibbles, bpl);
        syncing = true;
        try {
            hexArea.setText(body);
        } finally {
            syncing = false;
        }
        afterChange();
        hexArea.setCaretPosition(Math.min(PacketHex.nibblePosition(caretNibble, bpl), body.length()));
    }

    /** Status line: caret offset, selected block and its length (hex and decimal), like HxD. */
    private void updateStatus() {
        int textLen = hexArea.getDocument().getLength();
        int caret = hexArea.getCaretPosition();
        int offset = caret >= textLen ? data.length : Math.min(PacketHex.byteIndexAt(caret, bytesPerLine), data.length);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("오프셋(h): %X (%d)", offset, offset));
        int selStart = hexArea.getSelectionStart();
        int selEnd = hexArea.getSelectionEnd();
        if (selStart != selEnd && data.length > 0) {
            int first = PacketHex.byteIndexAt(selStart, bytesPerLine);
            int last = Math.min(PacketHex.byteIndexAt(selEnd - 1, bytesPerLine), data.length - 1);
            if (first <= last) {
                int len = last - first + 1;
                sb.append(String.format("    블록(h): %X-%X    길이(h): %X (%d)", first, last, len, len));
            }
        }
        sb.append(String.format("    전체: %d바이트", data.length));
        statusLabel.setText(sb.toString());
    }

    public void setBytes(byte[] bytes) {
        this.data = bytes != null ? bytes.clone() : new byte[0];
        render();
        hexArea.setCaretPosition(0);
        asciiArea.setCaretPosition(0);
    }

    /** Returns the current bytes (a trailing half byte still being typed is ignored). */
    public byte[] getBytes() {
        return data.clone();
    }

    /** Redraws both views from {@link #data}. */
    private void render() {
        nibbles = PacketHex.toCompactHex(data);
        syncing = true;
        try {
            hexArea.setText(PacketHex.formatBody(nibbles, bytesPerLine));
            asciiArea.setText(PacketHex.formatAscii(data));
        } finally {
            syncing = false;
        }
        afterChange();
    }

    private void afterChange() {
        String offsets = PacketHex.formatOffsets(nibbles.length(), bytesPerLine);
        if (!offsets.equals(offsetArea.getText())) offsetArea.setText(offsets);
        hexUtilPanel.setData(data);
        updateStatus();
    }

    /** Applies a new digit string typed into the hex view and re-lays it out. */
    private void applyHexEdit(DocumentFilter.FilterBypass fb, String newNibbles, int caretNibble)
            throws BadLocationException {
        nibbles = newNibbles;
        data = PacketHex.fromCompactHex(newNibbles);
        String body = PacketHex.formatBody(newNibbles, bytesPerLine);
        syncing = true;
        try {
            fb.replace(0, fb.getDocument().getLength(), body, null);
            asciiArea.setText(PacketHex.formatAscii(data));
        } finally {
            syncing = false;
        }
        afterChange();
        hexArea.setCaretPosition(Math.min(PacketHex.nibblePosition(caretNibble, bytesPerLine), body.length()));
    }

    private void onAsciiEdited() {
        if (syncing) return;
        data = PacketHex.parseAscii(asciiArea.getText());
        nibbles = PacketHex.toCompactHex(data);
        // Rewriting the hex document from inside this listener is fine: it is a different document.
        syncing = true;
        try {
            hexArea.setText(PacketHex.formatBody(nibbles, bytesPerLine));
        } finally {
            syncing = false;
        }
        afterChange();
    }

    /**
     * Turns every edit of the hex view into an edit of the digit string. Typed
     * or pasted text contributes only its hex digits; deleting a separator
     * (space/line break) deletes the neighbouring digit instead.
     */
    private final class HexFilter extends DocumentFilter {

        @Override
        public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr)
                throws BadLocationException {
            replace(fb, offset, 0, string, attr);
        }

        @Override
        public void remove(FilterBypass fb, int offset, int length) throws BadLocationException {
            if (syncing) {
                fb.remove(offset, length);
                return;
            }
            String text = fb.getDocument().getText(0, fb.getDocument().getLength());
            int start = PacketHex.nibbleCountBefore(text, offset);
            int end = PacketHex.nibbleCountBefore(text, offset + length);
            if (start == end && length == 1) {
                boolean backspace = hexArea.getCaretPosition() == offset + 1;
                if (backspace && start > 0) {
                    start--;
                } else if (!backspace && end < nibbles.length()) {
                    end++;
                } else {
                    return;
                }
            }
            applyHexEdit(fb, nibbles.substring(0, start) + nibbles.substring(end), start);
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String string, AttributeSet attrs)
                throws BadLocationException {
            if (syncing) {
                fb.replace(offset, length, string, attrs);
                return;
            }
            String inserted = PacketHex.extractHexDigits(string);
            if (inserted.isEmpty() && length == 0) {
                if (string != null && !string.isEmpty()) UIManager.getLookAndFeel().provideErrorFeedback(hexArea);
                return;
            }
            String text = fb.getDocument().getText(0, fb.getDocument().getLength());
            int start = PacketHex.nibbleCountBefore(text, offset);
            int end = PacketHex.nibbleCountBefore(text, offset + length);
            applyHexEdit(fb, nibbles.substring(0, start) + inserted + nibbles.substring(end),
                    start + inserted.length());
        }
    }
}
