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
    private final HexEditor requestEditor = new HexEditor(true);
    private final HexEditor responseEditor = new HexEditor(true);
    private final JCheckBox enabledCheck = new JCheckBox("이 규칙 변환 모드 사용");
    private boolean confirmed = false;

    public TransformRuleDialog(Frame owner, TransformRule rule) {
        super(owner, "변환 규칙 편집 (" + rule.getProtocol() + ")", true);
        this.rule = rule;

        requestEditor.setBytes(rule.getRequest());
        responseEditor.setBytes(rule.getResponse());
        enabledCheck.setSelected(rule.isEnabled());

        JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                titled("Request (이 요청이 오면)", requestEditor),
                titled("Response (이 응답을 보냄)", responseEditor));
        center.setResizeWeight(0.5);

        JButton okButton = new JButton("확인");
        JButton cancelButton = new JButton("취소");
        okButton.addActionListener(e -> onOk());
        cancelButton.addActionListener(e -> {
            confirmed = false;
            dispose();
        });

        JPanel south = new JPanel(new BorderLayout());
        south.add(enabledCheck, BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(okButton);
        buttons.add(cancelButton);
        south.add(buttons, BorderLayout.EAST);

        setLayout(new BorderLayout());
        add(center, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        setSize(760, 560);
        setLocationRelativeTo(owner);
    }

    private static JComponent titled(String title, JComponent content) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private void onOk() {
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
