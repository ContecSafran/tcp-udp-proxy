package com.proxy;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * One proxy project as a self-contained tab: connection settings, transform
 * rules, received packets, log — and its own {@link ProxyService} instance, so
 * every tab can run an independent proxy.
 */
public class ProxyPanel extends JPanel {

    private static final int MAX_PACKETS = 2000;
    private static final int CHECKBOX_HIT_WIDTH = 26; // px from the left that toggles the enable checkbox

    public static final String UNTITLED = "(새 프로젝트)";

    private final ProxyApp host;

    private final JTextField localPortField = new JTextField("25000", 6);
    private final JTextField targetIpField = new JTextField("127.0.0.1", 12);
    private final JTextField targetPortField = new JTextField("25001", 6);
    private final FlatButton startButton = new FlatButton("Start", UiTheme.SUCCESS, UiTheme.SUCCESS_HOVER);
    private final FlatButton stopButton = new FlatButton("Stop", UiTheme.DANGER, UiTheme.DANGER_HOVER);
    private final FlatButton clearPacketsButton = new FlatButton("Clear Packets", UiTheme.NEUTRAL, UiTheme.NEUTRAL_HOVER);
    private final FlatButton openButton = new FlatButton("Open", UiTheme.NEUTRAL, UiTheme.NEUTRAL_HOVER);
    private final FlatButton saveConfigButton = new FlatButton("Save", UiTheme.ACCENT, UiTheme.ACCENT_HOVER);
    private final FlatButton saveAsButton = new FlatButton("Save As", UiTheme.ACCENT, UiTheme.ACCENT_HOVER);
    private final FlatButton rulesJsonButton = new FlatButton("Project JSON", UiTheme.ACCENT, UiTheme.ACCENT_HOVER);
    /** Enabled only while a response packet (Target/Proxy -> Client) is selected. */
    private final FlatButton toRuleButton = new FlatButton("→ 변환 규칙", UiTheme.ACCENT, UiTheme.ACCENT_HOVER);

    /** Project name this tab is bound to; {@code null} until the first save. */
    private String currentProject;
    private final JLabel statusPill = UiTheme.pill("●  STOPPED", UiTheme.DANGER, Color.WHITE);
    private final JTextArea logArea = new JTextArea();
    private final JLabel tcpConnectionsLabel = UiTheme.pill("TCP  0", UiTheme.NEUTRAL, Color.WHITE);
    private final JLabel udpSessionsLabel = UiTheme.pill("UDP  0", UiTheme.NEUTRAL, Color.WHITE);

    // Transform rules (request -> response, individually enabled).
    private final DefaultListModel<TransformRule> transformListModel = new DefaultListModel<>();
    private final JList<TransformRule> transformList = new JList<>(transformListModel);
    private volatile List<TransformRule> transformRulesSnapshot = new ArrayList<>();

    // Received packets and their (read-only) hex/ASCII inspector.
    private final DefaultListModel<PacketInfo> packetListModel = new DefaultListModel<>();
    private final JList<PacketInfo> packetList = new JList<>(packetListModel);
    private final HexEditor receivedEditor = new HexEditor(false, true);

    private ProxyService proxyService;
    private boolean running;

    public ProxyPanel(ProxyApp host, String projectName) {
        this.host = host;
        setLayout(new BorderLayout());
        setBackground(UiTheme.APP_BG);

        add(buildControls(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        stopButton.setEnabled(false);

        startButton.addActionListener(e -> startProxy());
        stopButton.addActionListener(e -> stopProxy());
        clearPacketsButton.addActionListener(e -> clearPackets());
        openButton.addActionListener(e -> openProject());
        saveConfigButton.addActionListener(e -> saveProject());
        saveAsButton.addActionListener(e -> saveProjectAs());
        rulesJsonButton.addActionListener(e -> openRulesJson());

        toRuleButton.setEnabled(false);
        packetList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            PacketInfo selected = packetList.getSelectedValue();
            if (selected != null) {
                receivedEditor.setBytes(selected.getData());
            }
            // A rule is built from a response, so only response packets qualify.
            toRuleButton.setEnabled(selected != null && selected.isToClient());
        });

        transformList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int index = transformList.locationToIndex(e.getPoint());
                if (index < 0) return;
                Rectangle cell = transformList.getCellBounds(index, index);
                if (cell == null || !cell.contains(e.getPoint())) return;

                TransformRule rule = transformListModel.get(index);
                if (e.getX() - cell.x < CHECKBOX_HIT_WIDTH) {
                    // Clicked the enable checkbox area.
                    rule.setEnabled(!rule.isEnabled());
                    refreshRulesSnapshot();
                    transformList.repaint();
                    autoSaveConfig();
                } else if (e.getClickCount() == 2) {
                    editRule(rule);
                }
            }
        });

        loadInitialProject(projectName);
    }

    // ------------------------------------------------------------------ tab API

    /** Project name bound to this tab, or {@code null} if never saved. */
    public String getProjectName() {
        return currentProject;
    }

    /** Name shown on the tab. */
    public String displayName() {
        return currentProject != null ? currentProject : UNTITLED;
    }

    public boolean isRunning() {
        return running;
    }

    /** Stops the proxy (if running); called when the tab or window closes. */
    public void shutdown() {
        if (proxyService != null) {
            proxyService.stop();
            proxyService = null;
        }
        running = false;
    }

    // ------------------------------------------------------------------ layout

    private JComponent buildControls() {
        JPanel form = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        form.setOpaque(false);
        UiTheme.styleField(localPortField);
        UiTheme.styleField(targetIpField);
        UiTheme.styleField(targetPortField);
        form.add(labeled("Local Port", localPortField));
        form.add(labeled("Target IP", targetIpField));
        form.add(labeled("Target Port", targetPortField));
        form.add(Box.createHorizontalStrut(8));
        form.add(alignBottom(startButton));
        form.add(alignBottom(stopButton));
        form.add(alignBottom(clearPacketsButton));
        form.add(alignBottom(openButton));
        form.add(alignBottom(saveConfigButton));
        form.add(alignBottom(saveAsButton));
        form.add(alignBottom(rulesJsonButton));

        JPanel pillBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 6));
        pillBox.setOpaque(false);
        pillBox.add(alignBottom(statusPill));

        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.add(form, BorderLayout.CENTER);
        row.add(pillBox, BorderLayout.EAST);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(UiTheme.APP_BG);
        wrapper.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));
        wrapper.add(UiTheme.card("Connection", row), BorderLayout.CENTER);
        return wrapper;
    }

    private JComponent buildCenter() {
        UiTheme.styleList(transformList);
        transformList.setCellRenderer(new TransformRuleRenderer());
        UiTheme.styleList(packetList);

        UiTheme.styleArea(logArea, UiTheme.CONSOLE_BG, UiTheme.CONSOLE_FG, true);
        logArea.setEditable(false);

        // Transform Rules — fixed width, just wide enough for the action buttons.
        JComponent transformCard = buildTransformPanel();
        fixWidth(transformCard, 250);

        // Received Packets — fixed width, enough to show up to the direction info.
        JComponent receivedCard = buildReceivedPanel();
        fixWidth(receivedCard, 420);

        JPanel leftBox = new JPanel();
        leftBox.setOpaque(false);
        leftBox.setLayout(new BoxLayout(leftBox, BoxLayout.X_AXIS));
        leftBox.add(transformCard);
        leftBox.add(Box.createHorizontalStrut(8));
        leftBox.add(receivedCard);

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        top.add(leftBox, BorderLayout.WEST);
        top.add(UiTheme.card("Received Packet View", receivedEditor), BorderLayout.CENTER);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                top,
                UiTheme.card("Log", UiTheme.scroll(logArea)));
        UiTheme.styleSplit(mainSplit);
        mainSplit.setResizeWeight(0.62);

        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(UiTheme.APP_BG);
        center.setBorder(BorderFactory.createEmptyBorder(4, 12, 8, 12));
        center.add(mainSplit, BorderLayout.CENTER);
        return center;
    }

    /** Pins a component to a fixed width while letting it stretch vertically. */
    private static void fixWidth(JComponent c, int width) {
        c.setPreferredSize(new Dimension(width, 10));
        c.setMinimumSize(new Dimension(width, 10));
        c.setMaximumSize(new Dimension(width, Integer.MAX_VALUE));
    }

    private JComponent buildStatusBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        bar.setBackground(UiTheme.APP_BG);
        bar.setBorder(BorderFactory.createEmptyBorder(0, 14, 8, 14));
        bar.add(tcpConnectionsLabel);
        bar.add(udpSessionsLabel);
        return bar;
    }

    private JComponent buildTransformPanel() {
        FlatButton addButton = new FlatButton("추가", UiTheme.ACCENT, UiTheme.ACCENT_HOVER);
        FlatButton editButton = new FlatButton("편집", UiTheme.NEUTRAL, UiTheme.NEUTRAL_HOVER);
        FlatButton removeButton = new FlatButton("삭제", UiTheme.NEUTRAL, UiTheme.NEUTRAL_HOVER);
        addButton.setToolTipText("새 변환 규칙을 직접 추가");
        editButton.setToolTipText("선택한 규칙의 request/response 편집");
        addButton.addActionListener(e -> addNewRule());
        editButton.addActionListener(e -> {
            TransformRule rule = transformList.getSelectedValue();
            if (rule != null) editRule(rule);
        });
        removeButton.addActionListener(e -> removeSelectedRule());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(addButton);
        buttons.add(editButton);
        buttons.add(removeButton);

        JScrollPane transformScroll = UiTheme.scroll(transformList);
        transformScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setOpaque(false);
        body.add(transformScroll, BorderLayout.CENTER);
        body.add(buttons, BorderLayout.SOUTH);
        return UiTheme.card("Transform Rules", body);
    }

    private JComponent buildReceivedPanel() {
        FlatButton saveButton = new FlatButton("패킷 저장", UiTheme.NEUTRAL, UiTheme.NEUTRAL_HOVER);
        toRuleButton.setToolTipText("선택한 응답 패킷(Target/Proxy -> Client)으로 변환 규칙 생성: "
                + "짝이 되는 요청(Client -> Target)이 request, 이 응답이 response");
        saveButton.setToolTipText("수신 패킷 전체를 시각 기반 파일명으로 저장");
        toRuleButton.addActionListener(e -> sendReceivedToTransform());
        saveButton.addActionListener(e -> saveReceivedPackets());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(toRuleButton);
        buttons.add(saveButton);

        JScrollPane packetScroll = UiTheme.scroll(packetList);
        packetScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setOpaque(false);
        body.add(packetScroll, BorderLayout.CENTER);
        body.add(buttons, BorderLayout.SOUTH);
        return UiTheme.card("Received Packets", body);
    }

    private JComponent labeled(String text, JComponent field) {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JLabel label = new JLabel(text.toUpperCase());
        label.setFont(UiTheme.SECTION);
        label.setForeground(UiTheme.MUTED);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        field.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(label);
        panel.add(Box.createVerticalStrut(3));
        panel.add(field);
        return panel;
    }

    private JComponent alignBottom(JComponent c) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.add(Box.createVerticalStrut(16), BorderLayout.NORTH);
        panel.add(c, BorderLayout.CENTER);
        return panel;
    }

    private Frame ownerFrame() {
        Window window = SwingUtilities.getWindowAncestor(this);
        return window instanceof Frame ? (Frame) window : null;
    }

    // ------------------------------------------------------------------ actions

    /** Creates a new, empty transform rule (protocol chosen by the user). */
    private void addNewRule() {
        Object[] protocols = {"TCP", "UDP"};
        int choice = JOptionPane.showOptionDialog(this, "프로토콜을 선택하세요.", "새 변환 규칙",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, protocols, protocols[0]);
        if (choice < 0) return;

        String protocol = (String) protocols[choice];
        String defaultName = "Rule " + (transformListModel.size() + 1);
        TransformRule rule = new TransformRule(defaultName, protocol, new byte[0], new byte[0], true);
        transformListModel.addElement(rule);
        transformList.setSelectedValue(rule, true);
        refreshRulesSnapshot();
        autoSaveConfig();
        editRule(rule);
    }

    /**
     * Creates a transform rule from the selected response packet: the request
     * that triggered it (Client -> Target) becomes the rule's request, and the
     * selected response (Target/Proxy -> Client) becomes its response.
     */
    private void sendReceivedToTransform() {
        PacketInfo selected = packetList.getSelectedValue();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "수신 패킷 리스트에서 패킷을 선택하세요.");
            return;
        }
        if (!selected.isToClient()) {
            JOptionPane.showMessageDialog(this,
                    "응답 패킷(Target -> Client 또는 Proxy -> Client)을 선택하세요.\n"
                            + "요청(Client -> Target)은 짝이 되는 응답에서 자동으로 채워집니다.");
            return;
        }

        PacketInfo request = precedingRequest(packetList.getSelectedIndex(), selected);
        if (request == null) {
            JOptionPane.showMessageDialog(this,
                    "이 응답에 대응하는 요청(Client -> Target)을 찾지 못했습니다.\n"
                            + "request는 비어 있으니 편집창에서 직접 입력하세요.");
        }

        byte[] requestBytes = request != null ? request.getData().clone() : new byte[0];
        byte[] responseBytes = selected.getData().clone();
        String defaultName = "Rule " + (transformListModel.size() + 1);
        TransformRule rule = new TransformRule(defaultName, selected.getProtocol(),
                requestBytes, responseBytes, true);
        transformListModel.addElement(rule);
        transformList.setSelectedValue(rule, true);
        refreshRulesSnapshot();
        autoSaveConfig();
        log(String.format("Rule from packet pair: request %d byte(s), response %d byte(s).",
                requestBytes.length, responseBytes.length));
        editRule(rule);
    }

    /**
     * Walks back from the selected response to the nearest request on the same
     * protocol and peer — the one this response answers.
     */
    private PacketInfo precedingRequest(int responseIndex, PacketInfo response) {
        for (int i = responseIndex - 1; i >= 0; i--) {
            PacketInfo candidate = packetListModel.get(i);
            if (candidate.isFromClient()
                    && candidate.getProtocol().equals(response.getProtocol())
                    && candidate.getRemoteAddress().equals(response.getRemoteAddress())) {
                return candidate;
            }
        }
        return null;
    }

    /** Writes all received packets to a timestamp-named file under {@code captures/}. */
    private void saveReceivedPackets() {
        if (packetListModel.isEmpty()) {
            JOptionPane.showMessageDialog(this, "저장할 수신 패킷이 없습니다.");
            return;
        }
        File dir = new File(ProxyConfig.baseDir(), "captures");
        dir.mkdirs();
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File file = new File(dir, "packets_" + stamp + ".txt");

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < packetListModel.size(); i++) {
            PacketInfo p = packetListModel.get(i);
            sb.append(p).append('\n');
            sb.append(PacketHex.formatHex(p.getData()));
            sb.append("ASCII: ").append(PacketHex.formatAscii(p.getData())).append('\n');
            sb.append('\n');
        }
        try {
            Files.write(file.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            log("Saved " + packetListModel.size() + " packet(s) to " + file.getAbsolutePath());
        } catch (IOException ex) {
            log("Error saving packets: " + ex.getMessage());
        }
    }

    private void editRule(TransformRule rule) {
        TransformRuleDialog dialog = new TransformRuleDialog(ownerFrame(), rule);
        if (dialog.showDialog()) {
            refreshRulesSnapshot();
            transformList.repaint();
            autoSaveConfig();
        }
    }

    private void removeSelectedRule() {
        int index = transformList.getSelectedIndex();
        if (index < 0) return;
        transformListModel.remove(index);
        refreshRulesSnapshot();
        autoSaveConfig();
    }

    /** Opens the JSON view/editor for the whole project; applying saves it. */
    private void openRulesJson() {
        RulesJsonDialog dialog = new RulesJsonDialog(ownerFrame(), buildConfig().toJson(), this::applyConfigFromJson);
        dialog.setVisible(true);
    }

    private void applyConfigFromJson(ProxyConfig config) {
        applyConfig(config);
        autoSaveConfig();
        log("Applied project JSON (" + config.rules.size() + " rule(s)).");
    }

    /** Populates the UI fields and rule list from a config. */
    private void applyConfig(ProxyConfig config) {
        localPortField.setText(config.localPort);
        targetIpField.setText(config.targetIp);
        targetPortField.setText(config.targetPort);
        transformListModel.clear();
        for (TransformRule rule : config.rules) {
            transformListModel.addElement(rule);
        }
        refreshRulesSnapshot();
        transformList.repaint();
    }

    private List<TransformRule> currentRules() {
        List<TransformRule> rules = new ArrayList<>(transformListModel.size());
        for (int i = 0; i < transformListModel.size(); i++) {
            rules.add(transformListModel.get(i));
        }
        return rules;
    }

    /** Rebuilds the immutable snapshot the network threads read for rule matching. */
    private void refreshRulesSnapshot() {
        List<TransformRule> snapshot = new ArrayList<>(transformListModel.size());
        for (int i = 0; i < transformListModel.size(); i++) {
            snapshot.add(transformListModel.get(i));
        }
        transformRulesSnapshot = snapshot;
    }

    private void addPacket(PacketInfo packet) {
        SwingUtilities.invokeLater(() -> {
            if (packetListModel.size() >= MAX_PACKETS) {
                packetListModel.remove(0);
            }
            packetListModel.addElement(packet);
            packetList.setSelectedIndex(packetListModel.size() - 1);
            packetList.ensureIndexIsVisible(packetListModel.size() - 1);
            receivedEditor.setBytes(packet.getData());
        });
    }

    private void clearPackets() {
        packetListModel.clear();
        receivedEditor.setBytes(null);
        toRuleButton.setEnabled(false);
    }

    /**
     * Loads this tab's project at creation, migrating a legacy config if the
     * project file does not exist yet. A {@code null} name means a brand-new,
     * unsaved tab that keeps the UI defaults.
     */
    private void loadInitialProject(String name) {
        if (name == null) {
            return;
        }
        try {
            if (ProjectStore.exists(name)) {
                applyConfig(ProjectStore.load(name));
            } else {
                ProxyConfig legacy = ProxyConfig.loadLegacy();
                if (legacy != null) {
                    applyConfig(legacy);
                    ProjectStore.save(name, legacy); // migrate to <name>.json
                    log("Migrated legacy config to project '" + name + "'.");
                }
                // else: keep UI defaults; file is created on first save.
            }
        } catch (IOException | RuntimeException ex) {
            log("Error loading project '" + name + "': " + ex.getMessage());
        }
        currentProject = name;
    }

    private ProxyConfig buildConfig() {
        ProxyConfig config = new ProxyConfig();
        config.localPort = localPortField.getText();
        config.targetIp = targetIpField.getText();
        config.targetPort = targetPortField.getText();
        config.rules.addAll(currentRules());
        return config;
    }

    /** "Save" — writes the current project file (asks for a name if unsaved). */
    private void saveProject() {
        if (currentProject == null) {
            saveProjectAs();
            return;
        }
        try {
            ProjectStore.save(currentProject, buildConfig());
            log("Saved project '" + currentProject + "' -> " + ProjectStore.projectFile(currentProject).getAbsolutePath());
        } catch (IOException ex) {
            log("Error saving project: " + ex.getMessage());
        }
    }

    /** "Save As" — prompts for a project name and saves under it. */
    private void saveProjectAs() {
        String name = JOptionPane.showInputDialog(this, "프로젝트 이름을 입력하세요.",
                currentProject != null ? currentProject : "");
        if (name == null) return;
        name = ProjectStore.sanitize(name);
        if (host != null && host.isProjectOpenElsewhere(this, name)) {
            JOptionPane.showMessageDialog(this, "'" + name + "' 프로젝트는 이미 다른 탭에 열려 있습니다.");
            return;
        }
        if (ProjectStore.exists(name)
                && JOptionPane.showConfirmDialog(this, "'" + name + "' 프로젝트가 이미 있습니다. 덮어쓸까요?",
                    "덮어쓰기 확인", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        currentProject = name;
        saveProject();
        notifyHost();
    }

    /** "Open" — lets the user pick an existing project file and loads it. */
    private void openProject() {
        List<String> projects = ProjectStore.listProjects();
        if (projects.isEmpty()) {
            JOptionPane.showMessageDialog(this, "저장된 프로젝트가 없습니다. 먼저 [Save As]로 저장하세요.");
            return;
        }
        String selected = (String) JOptionPane.showInputDialog(this, "열 프로젝트를 선택하세요.", "프로젝트 열기",
                JOptionPane.QUESTION_MESSAGE, null, projects.toArray(), currentProject);
        if (selected == null) return;
        if (host != null && host.focusProjectTab(this, selected)) {
            return; // already open in another tab — switched to it instead
        }
        try {
            applyConfig(ProjectStore.load(selected));
            currentProject = selected;
            notifyHost();
            log("Opened project '" + selected + "'.");
        } catch (IOException | RuntimeException ex) {
            log("Error opening project '" + selected + "': " + ex.getMessage());
        }
    }

    /** Persists the current project silently; used whenever the rules change. */
    private void autoSaveConfig() {
        if (currentProject == null) {
            return; // unsaved tab — nothing to write yet
        }
        try {
            ProjectStore.save(currentProject, buildConfig());
        } catch (IOException ex) {
            log("Error saving project: " + ex.getMessage());
        }
    }

    private void notifyHost() {
        if (host != null) {
            host.panelStateChanged(this);
        }
    }

    private void startProxy() {
        try {
            int localPort = Integer.parseInt(localPortField.getText().trim());
            String targetIp = targetIpField.getText().trim();
            int targetPort = Integer.parseInt(targetPortField.getText().trim());

            proxyService = new ProxyService(localPort, targetIp, targetPort,
                    this::log, this::updateTcpConnectionCount, this::updateUdpSessionCount, this::addPacket);
            proxyService.setTransformRulesSupplier(() -> transformRulesSnapshot);
            proxyService.start();

            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            localPortField.setEnabled(false);
            targetIpField.setEnabled(false);
            targetPortField.setEnabled(false);
            setRunning(true);

        } catch (NumberFormatException ex) {
            log("Error: Invalid port number.");
        } catch (IOException ex) {
            log("Error starting proxy: " + ex.getMessage());
        }
    }

    private void stopProxy() {
        if (proxyService != null) {
            proxyService.stop();
            proxyService = null;
        }
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        localPortField.setEnabled(true);
        targetIpField.setEnabled(true);
        targetPortField.setEnabled(true);
        setRunning(false);
    }

    private void setRunning(boolean running) {
        this.running = running;
        statusPill.setText(running ? "●  RUNNING" : "●  STOPPED");
        statusPill.setBackground(running ? UiTheme.SUCCESS : UiTheme.DANGER);
        statusPill.repaint();
        notifyHost();
    }

    private void log(String message) {
        if (SwingUtilities.isEventDispatchThread()) {
            logArea.append(message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        } else {
            SwingUtilities.invokeLater(() -> {
                logArea.append(message + "\n");
                logArea.setCaretPosition(logArea.getDocument().getLength());
            });
        }
    }

    private void updateTcpConnectionCount() {
        SwingUtilities.invokeLater(() -> {
            int count = proxyService != null ? proxyService.getActiveTcpConnections() : 0;
            tcpConnectionsLabel.setText("TCP  " + count);
            tcpConnectionsLabel.setBackground(count > 0 ? UiTheme.SUCCESS : UiTheme.NEUTRAL);
            tcpConnectionsLabel.repaint();
        });
    }

    private void updateUdpSessionCount() {
        SwingUtilities.invokeLater(() -> {
            int count = proxyService != null ? proxyService.getActiveUdpSessions() : 0;
            udpSessionsLabel.setText("UDP  " + count);
            udpSessionsLabel.setBackground(count > 0 ? UiTheme.SUCCESS : UiTheme.NEUTRAL);
            udpSessionsLabel.repaint();
        });
    }

    /** Renders each transform rule as a checkbox (enable state) plus its summary. */
    private static class TransformRuleRenderer extends JCheckBox implements ListCellRenderer<TransformRule> {
        @Override
        public Component getListCellRendererComponent(JList<? extends TransformRule> list, TransformRule value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            setText(value.getName().isEmpty() ? "(이름 없음)" : value.getName());
            setSelected(value.isEnabled());
            setFont(UiTheme.BASE);
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            if (isSelected) {
                setBackground(UiTheme.SELECTION_BG);
                setForeground(UiTheme.SELECTION_FG);
            } else {
                setBackground(UiTheme.SURFACE);
                setForeground(value.isEnabled() ? UiTheme.TEXT : UiTheme.MUTED);
            }
            return this;
        }
    }
}
