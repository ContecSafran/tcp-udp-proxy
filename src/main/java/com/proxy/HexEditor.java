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

        JScrollPane hexScroll = UiTheme.scroll(hexArea);
        JScrollPane asciiScroll = UiTheme.scroll(asciiArea);
        // Equal small preferred widths so the split honours resizeWeight instead of
        // letting the wide hex dump push the ASCII column to almost nothing.
        hexScroll.setPreferredSize(new Dimension(10, 10));
        asciiScroll.setPreferredSize(new Dimension(10, 10));

        JPanel hexPanel = new JPanel(new BorderLayout());
        hexPanel.setBackground(UiTheme.SURFACE);
        hexPanel.add(hexScroll, BorderLayout.CENTER);
        hexPanel.add(hexUtilPanel, BorderLayout.SOUTH);

        int orientation = vertical ? JSplitPane.VERTICAL_SPLIT : JSplitPane.HORIZONTAL_SPLIT;
        JSplitPane split = new JSplitPane(orientation,
                withTitle("Hex", hexPanel),
                withTitle("ASCII", asciiScroll));
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
        JLabel header = new JLabel(title.toUpperCase());
        header.setFont(UiTheme.SECTION);
        header.setForeground(UiTheme.MUTED);
        header.setBorder(BorderFactory.createEmptyBorder(0, 2, 4, 0));

        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBackground(UiTheme.SURFACE);
        panel.add(header, BorderLayout.NORTH);
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
