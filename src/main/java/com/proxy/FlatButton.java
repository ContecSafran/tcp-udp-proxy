package com.proxy;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * A flat, rounded, filled button with hover/press feedback — a lightweight
 * modern look without any external look-and-feel dependency.
 */
public class FlatButton extends JButton {

    private static final Color DISABLED = new Color(0xB8BEC9);

    private final Color base;
    private final Color hover;
    private final Color press;
    private boolean hovering;
    private boolean pressing;

    public FlatButton(String text, Color base, Color hover) {
        super(text);
        this.base = base;
        this.hover = hover;
        this.press = hover.darker();
        setForeground(Color.WHITE);
        setFont(UiTheme.BOLD);
        setFocusPainted(false);
        setBorderPainted(false);
        setContentAreaFilled(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setBorder(BorderFactory.createEmptyBorder(7, 16, 7, 16));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hovering = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovering = false;
                repaint();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                pressing = true;
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                pressing = false;
                repaint();
            }
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color fill = !isEnabled() ? DISABLED : pressing ? press : hovering ? hover : base;
        g2.setColor(fill);
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
        g2.dispose();
        super.paintComponent(g);
    }
}
