package com.proxy;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/**
 * A window showing the whole project (IP/ports + transform rules) as editable
 * JSON. "적용" parses the text and hands the resulting config to the supplied
 * handler (which updates the live settings and persists them). A bare rules
 * array is also accepted for compatibility with pasted rule-only JSON.
 */
public class RulesJsonDialog extends JDialog {

    private final JTextArea jsonArea = new JTextArea();
    private final Consumer<ProxyConfig> applyHandler;

    public RulesJsonDialog(Frame owner, String initialJson, Consumer<ProxyConfig> applyHandler) {
        super(owner, "프로젝트 JSON (IP/Port + 규칙)", true);
        this.applyHandler = applyHandler;

        UiTheme.styleArea(jsonArea, UiTheme.HEX_BG, UiTheme.HEX_FG, true);
        jsonArea.setText(initialJson);
        jsonArea.setCaretPosition(0);

        FlatButton applyButton = new FlatButton("적용", UiTheme.SUCCESS, UiTheme.SUCCESS_HOVER);
        FlatButton closeButton = new FlatButton("닫기", UiTheme.NEUTRAL, UiTheme.NEUTRAL_HOVER);
        applyButton.addActionListener(e -> apply());
        closeButton.addActionListener(e -> dispose());

        JLabel hint = new JLabel("JSON을 수정한 뒤 [적용]하면 IP/포트·규칙이 교체되고 프로젝트 파일에 저장됩니다.");
        hint.setForeground(UiTheme.MUTED);
        hint.setFont(UiTheme.BASE);

        JPanel south = new JPanel(new BorderLayout());
        south.setBackground(UiTheme.APP_BG);
        south.setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 12));
        south.add(hint, BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(applyButton);
        buttons.add(closeButton);
        south.add(buttons, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBackground(UiTheme.APP_BG);
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12));
        content.add(UiTheme.card("Project (JSON)", UiTheme.scroll(jsonArea)), BorderLayout.CENTER);

        setLayout(new BorderLayout());
        getContentPane().setBackground(UiTheme.APP_BG);
        add(content, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        setSize(760, 640);
        setLocationRelativeTo(owner);
    }

    private void apply() {
        ProxyConfig config;
        try {
            config = ProxyConfig.fromJson(jsonArea.getText());
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "JSON 파싱 오류:\n" + ex.getMessage(),
                    "적용 실패", JOptionPane.ERROR_MESSAGE);
            return;
        }
        applyHandler.accept(config);
        // Re-render the normalized JSON so the view reflects what was applied.
        jsonArea.setText(config.toJson());
        jsonArea.setCaretPosition(0);
        JOptionPane.showMessageDialog(this, "적용하고 저장했습니다. (규칙 " + config.rules.size() + "개)",
                "적용 완료", JOptionPane.INFORMATION_MESSAGE);
    }
}
