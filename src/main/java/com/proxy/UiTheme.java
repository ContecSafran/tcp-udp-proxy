package com.proxy;

import javax.swing.*;
import java.awt.*;

/**
 * Central palette, fonts and small styling helpers so every screen shares one
 * clean light theme. No external look-and-feel library is required.
 */
public final class UiTheme {

    private UiTheme() {
    }

    // Surfaces
    public static final Color APP_BG = new Color(0xEEF0F3);
    public static final Color SURFACE = new Color(0xFFFFFF);
    public static final Color HEADER_BG = new Color(0x2D3142);
    public static final Color HEADER_FG = new Color(0xF4F5F7);
    public static final Color BORDER = new Color(0xD7DBE0);

    // Text
    public static final Color TEXT = new Color(0x2B2D31);
    public static final Color MUTED = new Color(0x6B7280);

    // Accents
    public static final Color ACCENT = new Color(0x3B82F6);
    public static final Color ACCENT_HOVER = new Color(0x2F6FE0);
    public static final Color SUCCESS = new Color(0x1E9E52);
    public static final Color SUCCESS_HOVER = new Color(0x178345);
    public static final Color DANGER = new Color(0xDC2626);
    public static final Color DANGER_HOVER = new Color(0xBE1D1D);
    public static final Color NEUTRAL = new Color(0x64748B);
    public static final Color NEUTRAL_HOVER = new Color(0x51617A);

    // Consoles / data
    public static final Color CONSOLE_BG = new Color(0x1E1F22);
    public static final Color CONSOLE_FG = new Color(0x8FE388);
    public static final Color HEX_BG = new Color(0xFAFBFD);
    public static final Color HEX_FG = new Color(0x22252B);
    public static final Color SELECTION_BG = new Color(0xD5E5FF);
    public static final Color SELECTION_FG = new Color(0x142848);

    // Fonts — resolve to families that include Korean glyphs, falling back to
    // the logical Dialog/Monospaced fonts (which always support Korean) so text
    // never renders as tofu boxes.
    private static final String UI_FAMILY = resolveFamily(Font.DIALOG, "Malgun Gothic", "맑은 고딕", "Noto Sans CJK KR");
    private static final String MONO_FAMILY = resolveFamily(Font.MONOSPACED, "Consolas", "D2Coding", "Malgun Gothic");

    public static final Font BASE = new Font(UI_FAMILY, Font.PLAIN, 13);
    public static final Font BOLD = new Font(UI_FAMILY, Font.BOLD, 13);
    public static final Font TITLE = new Font(UI_FAMILY, Font.BOLD, 18);
    public static final Font SECTION = new Font(UI_FAMILY, Font.BOLD, 11);
    public static final Font MONO = new Font(MONO_FAMILY, Font.PLAIN, 13);

    /** Returns the first installed family from {@code preferred}, else {@code fallback}. */
    private static String resolveFamily(String fallback, String... preferred) {
        java.util.Set<String> installed = new java.util.HashSet<>(java.util.Arrays.asList(
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String family : preferred) {
            if (installed.contains(family)) {
                return family;
            }
        }
        return fallback;
    }

    /** Applies base fonts/colors to shared component defaults. Call once at startup. */
    public static void installDefaults() {
        UIManager.put("Label.font", BASE);
        UIManager.put("Button.font", BASE);
        UIManager.put("CheckBox.font", BASE);
        UIManager.put("ComboBox.font", BASE);
        UIManager.put("TextField.font", BASE);
        UIManager.put("List.font", BASE);
        UIManager.put("ToolTip.font", BASE);
        UIManager.put("OptionPane.messageFont", BASE);
        UIManager.put("OptionPane.buttonFont", BASE);
    }

    /** A titled "card" surface with a subtle rounded border and a muted header. */
    public static JPanel card(String title, JComponent content) {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBackground(SURFACE);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1, true),
                BorderFactory.createEmptyBorder(8, 10, 10, 10)));
        if (title != null) {
            JLabel header = new JLabel(title.toUpperCase());
            header.setFont(SECTION);
            header.setForeground(MUTED);
            panel.add(header, BorderLayout.NORTH);
        }
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    public static void styleField(JTextField field) {
        field.setFont(BASE);
        field.setBackground(SURFACE);
        field.setForeground(TEXT);
        field.setCaretColor(TEXT);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1, true),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)));
    }

    public static void styleArea(JTextArea area, Color bg, Color fg, boolean mono) {
        area.setBackground(bg);
        area.setForeground(fg);
        area.setCaretColor(fg);
        area.setFont(mono ? MONO : BASE);
        area.setSelectionColor(SELECTION_BG);
        area.setSelectedTextColor(SELECTION_FG);
        area.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
    }

    public static void styleList(JList<?> list) {
        list.setBackground(SURFACE);
        list.setForeground(TEXT);
        list.setSelectionBackground(SELECTION_BG);
        list.setSelectionForeground(SELECTION_FG);
        list.setFixedCellHeight(26);
        list.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
    }

    public static JScrollPane scroll(Component view) {
        JScrollPane pane = new JScrollPane(view);
        pane.setBorder(BorderFactory.createLineBorder(BORDER, 1, true));
        pane.getViewport().setBackground(view.getBackground());
        return pane;
    }

    public static void styleSplit(JSplitPane split) {
        split.setBorder(null);
        split.setDividerSize(8);
        split.setBackground(APP_BG);
        split.setContinuousLayout(true);
    }

    /** A rounded status pill (e.g. "● RUNNING"). */
    public static JLabel pill(String text, Color bg, Color fg) {
        JLabel label = new JLabel(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        label.setOpaque(false);
        label.setBackground(bg);
        label.setForeground(fg);
        label.setFont(BOLD);
        label.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        return label;
    }
}
