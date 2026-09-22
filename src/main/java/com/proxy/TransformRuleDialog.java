package com.proxy;

import javax.swing.*;
import java.awt.*;

/**
 * Modal editor for a single {@link TransformRule}: separate hex/ASCII editors
 * for the request to match and the response to send, plus an enable toggle.
 * Applies the edits to the rule only when the user confirms.
 */
public class TransformRuleDialog extends JDialog {

    private final TransformRule rule;
    private final JTextField nameField = new JTextField();
    private final HexEditor requestEditor = new HexEditor(true, true);
    private final HexEditor responseEditor = new HexEditor(true, true);
    private final JCheckBox enabledCheck = new JCheckBox("이 규칙 변환 모드 사용");
    private boolean confirmed = false;

    public TransformRuleDialog(Frame owner, TransformRule rule) {
        super(owner, "변환 규칙 편집 (" + rule.getProtocol() + ")", true);
        this.rule = rule;

        nameField.setText(rule.getName());
        requestEditor.setBytes(rule.getRequest());
        responseEditor.setBytes(rule.getResponse());
        enabledCheck.setSelected(rule.isEnabled());
        enabledCheck.setOpaque(false);
        enabledCheck.setForeground(UiTheme.TEXT);
        UiTheme.styleField(nameField);

        JLabel nameLabel = new JLabel("이름");
        nameLabel.setFont(UiTheme.SECTION);
        nameLabel.setForeground(UiTheme.MUTED);
        JPanel nameInner = new JPanel(new BorderLayout(8, 0));
        nameInner.setBackground(UiTheme.SURFACE);
        nameInner.add(nameLabel, BorderLayout.WEST);
        nameInner.add(nameField, BorderLayout.CENTER);
        JPanel namePanel = UiTheme.card("Rule Name", nameInner);

        JSplitPane center = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                UiTheme.card("Request (이 요청이 오면)", requestEditor),
                UiTheme.card("Response (이 응답을 보냄)", responseEditor));
        UiTheme.styleSplit(center);
        center.setResizeWeight(0.5);
        center.addComponentListener(new java.awt.event.ComponentAdapter() {
            private boolean done;
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                if (!done && center.getWidth() > 0) {
                    done = true;
                    center.setDividerLocation(0.5);
                }
            }
        });

        FlatButton okButton = new FlatButton("확인", UiTheme.SUCCESS, UiTheme.SUCCESS_HOVER);
        FlatButton cancelButton = new FlatButton("취소", UiTheme.NEUTRAL, UiTheme.NEUTRAL_HOVER);
        okButton.addActionListener(e -> onOk());
        cancelButton.addActionListener(e -> {
            confirmed = false;
            dispose();
        });

        JPanel south = new JPanel(new BorderLayout());
        south.setBackground(UiTheme.APP_BG);
        south.setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 12));
        south.add(enabledCheck, BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(okButton);
        buttons.add(cancelButton);
        south.add(buttons, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBackground(UiTheme.APP_BG);
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12));
        content.add(namePanel, BorderLayout.NORTH);
        content.add(center, BorderLayout.CENTER);

        setLayout(new BorderLayout());
        getContentPane().setBackground(UiTheme.APP_BG);
        add(content, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        setSize(1300, 640);
        setLocationRelativeTo(owner);
    }

    private void onOk() {
        rule.setName(nameField.getText().trim());
        rule.setRequest(requestEditor.getBytes());
        rule.setResponse(responseEditor.getBytes());
        rule.setEnabled(enabledCheck.isSelected());
        confirmed = true;
        dispose();
    }

    /** Shows the dialog and returns true if the user confirmed the edits. */
    public boolean showDialog() {
        setVisible(true);
        return confirmed;
    }
}
