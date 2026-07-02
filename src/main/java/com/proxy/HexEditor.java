package com.proxy;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;

/**
 * A reusable editor showing a byte payload as side-by-side editable hex and
 * ASCII views, kept in sync. Edits are committed back to the working buffer when
 * focus leaves an area (and on demand via {@link #getBytes()}), re-parsing only
 * the view the user actually changed so unedited ASCII placeholders ('.') never
 * clobber real bytes.
 */
public class HexEditor extends JPanel {

    private final JTextArea hexArea = new JTextArea();
    private final JTextArea asciiArea = new JTextArea();
    private final HexUtilPanel hexUtilPanel = new HexUtilPanel(hexArea);

    private byte[] data = new byte[0];
    private String lastRenderedHex = "";
    private String lastRenderedAscii = "";

    public HexEditor(boolean editable) {
        super(new BorderLayout());

        hexArea.setEditable(editable);
        hexArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        asciiArea.setEditable(editable);
        asciiArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JPanel hexPanel = new JPanel(new BorderLayout());
        hexPanel.add(new JScrollPane(hexArea), BorderLayout.CENTER);
        hexPanel.add(hexUtilPanel, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                withTitle("Hex", hexPanel),
                withTitle("ASCII", new JScrollPane(asciiArea)));
        split.setResizeWeight(0.6);
        add(split, BorderLayout.CENTER);

        if (editable) {
            hexArea.addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent e) {
                    commitHexEdit();
                }
            });
            asciiArea.addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent e) {
                    commitAsciiEdit();
                }
            });
        }
    }

    private static JComponent withTitle(String title, JComponent content) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    public void setBytes(byte[] bytes) {
        this.data = bytes != null ? bytes.clone() : new byte[0];
        render();
    }

    /** Returns the current bytes, flushing any pending in-focus edits first. */
    public byte[] getBytes() {
        commitHexEdit();
        commitAsciiEdit();
        return data;
    }

    private void render() {
        lastRenderedHex = PacketHex.formatHex(data);
        lastRenderedAscii = PacketHex.formatAscii(data);
        hexArea.setText(lastRenderedHex);
        asciiArea.setText(lastRenderedAscii);
        hexArea.setCaretPosition(0);
        asciiArea.setCaretPosition(0);
        hexUtilPanel.setData(data);
    }

    private void commitHexEdit() {
        String text = hexArea.getText();
        if (text.equals(lastRenderedHex)) return;
        data = PacketHex.parseHexDump(text);
        render();
    }

    private void commitAsciiEdit() {
        String text = asciiArea.getText();
        if (text.equals(lastRenderedAscii)) return;
        data = PacketHex.parseAscii(text);
        render();
    }
}
