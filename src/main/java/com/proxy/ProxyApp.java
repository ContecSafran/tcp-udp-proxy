package com.proxy;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ProxyApp extends JFrame {

    private static final int MAX_PACKETS = 2000;
    private static final int CHECKBOX_HIT_WIDTH = 24; // px from the left that toggles the enable checkbox

    private final JTextField localPortField = new JTextField("25000", 5);
    private final JTextField targetIpField = new JTextField("127.0.0.1", 15);
    private final JTextField targetPortField = new JTextField("25001", 5);
    private final JButton startButton = new JButton("Start");
    private final JButton stopButton = new JButton("Stop");
    private final JButton clearPacketsButton = new JButton("Clear Packets");
    private final JButton saveConfigButton = new JButton("Save Config");
    private final JTextArea logArea = new JTextArea();
    private final JLabel tcpConnectionsLabel = new JLabel("TCP Connections: 0");
    private final JLabel udpSessionsLabel = new JLabel("UDP Sessions: 0");

    // Transform rules (request -> response, individually enabled).
    private final DefaultListModel<TransformRule> transformListModel = new DefaultListModel<>();
    private final JList<TransformRule> transformList = new JList<>(transformListModel);
    private volatile List<TransformRule> transformRulesSnapshot = new ArrayList<>();

    // Received packets and their (read-only) hex/ASCII inspector.
    private final DefaultListModel<PacketInfo> packetListModel = new DefaultListModel<>();
    private final JList<PacketInfo> packetList = new JList<>(packetListModel);
    private final HexEditor receivedEditor = new HexEditor(false);

    private ProxyService proxyService;

    public ProxyApp() {
        setTitle("TCP/UDP Proxy");
        setSize(1200, 720);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        JPanel controlPanel = new JPanel(new FlowLayout());
        controlPanel.add(new JLabel("Local Port:"));
        controlPanel.add(localPortField);
        controlPanel.add(new JLabel("Target IP:"));
        controlPanel.add(targetIpField);
        controlPanel.add(new JLabel("Target Port:"));
        controlPanel.add(targetPortField);
        controlPanel.add(startButton);
        controlPanel.add(stopButton);
        controlPanel.add(clearPacketsButton);
        controlPanel.add(saveConfigButton);

        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusPanel.add(tcpConnectionsLabel);
        statusPanel.add(udpSessionsLabel);

        logArea.setEditable(false);

        transformList.setCellRenderer(new TransformRuleRenderer());

        JSplitPane listsSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                buildTransformPanel(),
                titled("Received Packets", new JScrollPane(packetList)));
        listsSplit.setResizeWeight(0.5);

        JSplitPane packetSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                listsSplit,
                titled("Received Packet View", receivedEditor));
        packetSplit.setResizeWeight(0.5);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                packetSplit,
                titled("Log", new JScrollPane(logArea)));
        mainSplit.setResizeWeight(0.6);

        add(controlPanel, BorderLayout.NORTH);
        add(mainSplit, BorderLayout.CENTER);
        add(statusPanel, BorderLayout.SOUTH);

        stopButton.setEnabled(false);

        startButton.addActionListener(e -> startProxy());
        stopButton.addActionListener(e -> stopProxy());
        clearPacketsButton.addActionListener(e -> clearPackets());
        saveConfigButton.addActionListener(e -> saveConfig());

        packetList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && packetList.getSelectedValue() != null) {
                receivedEditor.setBytes(packetList.getSelectedValue().getData());
            }
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
                } else if (e.getClickCount() == 2) {
                    editRule(rule);
                }
            }
        });

        loadConfig();
    }

    private void loadConfig() {
        try {
            ProxyConfig config = ProxyConfig.load();
            localPortField.setText(config.localPort);
            targetIpField.setText(config.targetIp);
            targetPortField.setText(config.targetPort);
            transformListModel.clear();
            for (TransformRule rule : config.rules) {
                transformListModel.addElement(rule);
            }
            refreshRulesSnapshot();
        } catch (IOException ex) {
            log("Error loading config: " + ex.getMessage());
        }
    }

    private void saveConfig() {
        ProxyConfig config = new ProxyConfig();
        config.localPort = localPortField.getText();
        config.targetIp = targetIpField.getText();
        config.targetPort = targetPortField.getText();
        for (int i = 0; i < transformListModel.size(); i++) {
            config.rules.add(transformListModel.get(i));
        }
        try {
            config.save();
            log("Config saved to " + ProxyConfig.configFile().getAbsolutePath());
        } catch (IOException ex) {
            log("Error saving config: " + ex.getMessage());
        }
    }

    private JPanel buildTransformPanel() {
        JPanel panel = titled("Transform Rules", new JScrollPane(transformList));

        JButton addButton = new JButton("← 추가");
        JButton editButton = new JButton("편집");
        JButton removeButton = new JButton("삭제");
        addButton.setToolTipText("수신 패킷을 request로 하는 변환 규칙 추가");
        editButton.setToolTipText("선택한 규칙의 request/response 편집");
        addButton.addActionListener(e -> addRuleFromSelectedPacket());
        editButton.addActionListener(e -> {
            TransformRule rule = transformList.getSelectedValue();
            if (rule != null) editRule(rule);
        });
        removeButton.addActionListener(e -> removeSelectedRule());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        buttons.add(addButton);
        buttons.add(editButton);
        buttons.add(removeButton);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private static JPanel titled(String title, JComponent content) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private void addRuleFromSelectedPacket() {
        PacketInfo selected = packetList.getSelectedValue();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "수신 패킷 리스트에서 패킷을 선택하세요.");
            return;
        }
        byte[] request = selected.getData().clone();
        // Seed the response with a copy of the request; the user edits it next.
        String defaultName = "Rule " + (transformListModel.size() + 1);
        TransformRule rule = new TransformRule(defaultName, selected.getProtocol(), request, request.clone(), true);
        transformListModel.addElement(rule);
        transformList.setSelectedValue(rule, true);
        refreshRulesSnapshot();
        editRule(rule);
    }

    private void editRule(TransformRule rule) {
        TransformRuleDialog dialog = new TransformRuleDialog(this, rule);
        if (dialog.showDialog()) {
            refreshRulesSnapshot();
            transformList.repaint();
        }
    }

    private void removeSelectedRule() {
        int index = transformList.getSelectedIndex();
        if (index < 0) return;
        transformListModel.remove(index);
        refreshRulesSnapshot();
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
    }

    private void startProxy() {
        try {
            int localPort = Integer.parseInt(localPortField.getText());
            String targetIp = targetIpField.getText();
            int targetPort = Integer.parseInt(targetPortField.getText());

            proxyService = new ProxyService(localPort, targetIp, targetPort,
                    this::log, this::updateTcpConnectionCount, this::updateUdpSessionCount, this::addPacket);
            proxyService.setTransformRulesSupplier(() -> transformRulesSnapshot);
            proxyService.start();

            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            localPortField.setEnabled(false);
            targetIpField.setEnabled(false);
            targetPortField.setEnabled(false);

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
    }

    private void log(String message) {
        // Ensure logging is done on the Event Dispatch Thread
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
            if (proxyService != null) {
                tcpConnectionsLabel.setText("TCP Connections: " + proxyService.getActiveTcpConnections());
            } else {
                tcpConnectionsLabel.setText("TCP Connections: 0");
            }
        });
    }

    private void updateUdpSessionCount() {
        SwingUtilities.invokeLater(() -> {
            if (proxyService != null) {
                udpSessionsLabel.setText("UDP Sessions: " + proxyService.getActiveUdpSessions());
            } else {
                udpSessionsLabel.setText("UDP Sessions: 0");
            }
        });
    }

    /** Renders each transform rule as a checkbox (enable state) plus its summary. */
    private static class TransformRuleRenderer extends JCheckBox implements ListCellRenderer<TransformRule> {
        @Override
        public Component getListCellRendererComponent(JList<? extends TransformRule> list, TransformRule value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            setText(value.toString());
            setSelected(value.isEnabled());
            setOpaque(true);
            if (isSelected) {
                setBackground(list.getSelectionBackground());
                setForeground(list.getSelectionForeground());
            } else {
                setBackground(list.getBackground());
                setForeground(list.getForeground());
            }
            return this;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ProxyApp proxyApp = new ProxyApp();
            proxyApp.setVisible(true);
        });
    }
}
