package com.proxy;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.Map;
import java.util.concurrent.*;

public class ProxyApp extends JFrame {

    private final JTextField localPortField = new JTextField("8080", 5);
    private final JTextField targetIpField = new JTextField("127.0.0.1", 15);
    private final JTextField targetPortField = new JTextField("80", 5);
    private final JButton startButton = new JButton("Start");
    private final JButton stopButton = new JButton("Stop");
    private final JTextArea logArea = new JTextArea();
    private final JLabel tcpConnectionsLabel = new JLabel("TCP Connections: 0");
    private final JLabel udpSessionsLabel = new JLabel("UDP Sessions: 0");

    private ExecutorService executorService;
    private ScheduledExecutorService scheduledExecutorService;
    private ServerSocket serverSocket;
    private DatagramSocket udpListener;

    private final Map<SocketAddress, UdpSession> udpSessions = new ConcurrentHashMap<>();
    private final AtomicInteger activeTcpConnections = new AtomicInteger(0);

    public ProxyApp() {
        setTitle("TCP/UDP Proxy");
        setSize(600, 400);
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

        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusPanel.add(tcpConnectionsLabel);
        statusPanel.add(udpSessionsLabel);

        add(controlPanel, BorderLayout.NORTH);
        add(new JScrollPane(logArea), BorderLayout.CENTER);
        add(statusPanel, BorderLayout.SOUTH);

        logArea.setEditable(false);
        stopButton.setEnabled(false);

        startButton.addActionListener(e -> startProxy());
        stopButton.addActionListener(e -> stopProxy());
    }

    private void startProxy() {
        try {
            int localPort = Integer.parseInt(localPortField.getText());
            String targetIp = targetIpField.getText();
            int targetPort = Integer.parseInt(targetPortField.getText());

            executorService = Executors.newCachedThreadPool();
            scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();

            // Start TCP Proxy
            serverSocket = new ServerSocket(localPort);
            executorService.submit(() -> acceptTcpConnections(targetIp, targetPort));

            // Start UDP Proxy
            udpListener = new DatagramSocket(localPort);
            executorService.submit(() -> listenForUdpPackets(targetIp, targetPort));

            // Start UDP session cleanup
            scheduledExecutorService.scheduleAtFixedRate(this::cleanupUdpSessions, 10, 10, TimeUnit.SECONDS);

            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            log("Proxy started on port " + localPort);
        } catch (NumberFormatException ex) {
            log("Error: Invalid port number.");
        } catch (IOException ex) {
            log("Error starting proxy: " + ex.getMessage());
        }
    }

    private void stopProxy() {
        log("Stopping proxy...");
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            if (udpListener != null && !udpListener.isClosed()) {
                udpListener.close();
            }
            if (executorService != null) {
                executorService.shutdownNow();
            }
            if (scheduledExecutorService != null) {
                scheduledExecutorService.shutdownNow();
            }
            udpSessions.values().forEach(UdpSession::close);
            udpSessions.clear();
            updateUdpSessionCount();
            log("Proxy stopped.");
        } catch (IOException e) {
            log("Error stopping proxy: " + e.getMessage());
        } finally {
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
        }
    }

    private void acceptTcpConnections(String targetIp, int targetPort) {
        while (!serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                activeTcpConnections.incrementAndGet();
                updateTcpConnectionCount();
                log("[TCP] Client connected: " + clientSocket.getRemoteSocketAddress());

                Socket targetSocket = new Socket(targetIp, targetPort);

                executorService.submit(() -> forwardData(clientSocket, targetSocket, "Client -> Target"));
                executorService.submit(() -> forwardData(targetSocket, clientSocket, "Target -> Client"));

            } catch (IOException e) {
                if (serverSocket.isClosed()) {
                    log("TCP listener stopped.");
                } else {
                    log("Error accepting TCP connection: " + e.getMessage());
                }
            }
        }
    }

    private void forwardData(Socket source, Socket destination, String direction) {
        try (InputStream input = source.getInputStream(); OutputStream output = destination.getOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                log(String.format("[TCP] %s (%s -> %s): %d bytes", direction, source.getRemoteSocketAddress(), destination.getRemoteSocketAddress(), read));
            }
        } catch (IOException e) {
            // Connection closed or error
        } finally {
            try {
                source.close();
                destination.close();
                activeTcpConnections.decrementAndGet();
                updateTcpConnectionCount();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    private void listenForUdpPackets(String targetIp, int targetPort) {
        byte[] buffer = new byte[65507];
        while (!udpListener.isClosed()) {
            try {
                DatagramPacket clientPacket = new DatagramPacket(buffer, buffer.length);
                udpListener.receive(clientPacket);
                SocketAddress clientAddress = clientPacket.getSocketAddress();

                UdpSession session = udpSessions.computeIfAbsent(clientAddress, addr -> {
                    try {
                        UdpSession newSession = new UdpSession(clientAddress, targetIp, targetPort, udpListener);
                        executorService.submit(newSession);
                        updateUdpSessionCount();
                        log("[UDP] New session for " + clientAddress);
                        return newSession;
                    } catch (SocketException e) {
                        log("Error creating UDP session: " + e.getMessage());
                        return null;
                    }
                });

                if (session != null) {
                    session.sendToServer(clientPacket);
                    log(String.format("[UDP] Client(%s) -> Target: %d bytes", clientAddress, clientPacket.getLength()));
                }
            } catch (IOException e) {
                if (udpListener.isClosed()) {
                    log("UDP listener stopped.");
                } else {
                    log("Error in UDP listener: " + e.getMessage());
                }
            }
        }
    }

    private void cleanupUdpSessions() {
        long now = System.currentTimeMillis();
        udpSessions.entrySet().removeIf(entry -> {
            if (now - entry.getValue().getLastActivity() > 60000) {
                entry.getValue().close();
                log("[UDP] Session timed out for " + entry.getKey());
                updateUdpSessionCount();
                return true;
            }
            return false;
        });
    }

    private void log(String message) {
        System.out.println(message);
        SwingUtilities.invokeLater(() -> {
            logArea.append(message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void updateTcpConnectionCount() {
        SwingUtilities.invokeLater(() -> tcpConnectionsLabel.setText("TCP Connections: " + activeTcpConnections.get()));
    }

    private void updateUdpSessionCount() {
        SwingUtilities.invokeLater(() -> udpSessionsLabel.setText("UDP Sessions: " + udpSessions.size()));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ProxyApp proxyApp = new ProxyApp();
            proxyApp.setVisible(true);
        });
    }

    private static class UdpSession implements Runnable {
        private final DatagramSocket serverFacingSocket;
        private final SocketAddress clientAddress;
        private final DatagramSocket mainUdpListener;
        private final InetSocketAddress targetAddress;
        private volatile long lastActivity;

        UdpSession(SocketAddress clientAddress, String targetIp, int targetPort, DatagramSocket mainUdpListener) throws SocketException {
            this.clientAddress = clientAddress;
            this.mainUdpListener = mainUdpListener;
            this.targetAddress = new InetSocketAddress(targetIp, targetPort);
            this.serverFacingSocket = new DatagramSocket();
            this.lastActivity = System.currentTimeMillis();
        }

        void sendToServer(DatagramPacket clientPacket) throws IOException {
            lastActivity = System.currentTimeMillis();
            DatagramPacket serverPacket = new DatagramPacket(
                    clientPacket.getData(),
                    clientPacket.getLength(),
                    targetAddress
            );
            serverFacingSocket.send(serverPacket);
        }

        @Override
        public void run() {
            byte[] buffer = new byte[65507];
            while (!serverFacingSocket.isClosed()) {
                try {
                    DatagramPacket serverResponse = new DatagramPacket(buffer, buffer.length);
                    serverFacingSocket.receive(serverResponse);
                    lastActivity = System.currentTimeMillis();

                    DatagramPacket clientResponse = new DatagramPacket(
                            serverResponse.getData(),
                            serverResponse.getLength(),
                            clientAddress
                    );
                    mainUdpListener.send(clientResponse);
                } catch (IOException e) {
                    if (!serverFacingSocket.isClosed()) {
                        // Log error
                    }
                }
            }
        }

        long getLastActivity() {
            return lastActivity;
        }

        void close() {
            serverFacingSocket.close();
        }
    }
}
