package com.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class ProxyService {

    private final int localPort;
    private final String targetIp;
    private final int targetPort;

    private ExecutorService executorService;
    private ScheduledExecutorService scheduledExecutorService;
    private ServerSocket serverSocket;
    private DatagramSocket udpListener;

    private final Map<SocketAddress, UdpSession> udpSessions = new ConcurrentHashMap<>();
    private final AtomicInteger activeTcpConnections = new AtomicInteger(0);

    // Callbacks for logging and UI updates
    private final Consumer<String> logger;
    private final Runnable tcpCountUpdater;
    private final Runnable udpCountUpdater;

    public ProxyService(int localPort, String targetIp, int targetPort, Consumer<String> logger, Runnable tcpCountUpdater, Runnable udpCountUpdater) {
        this.localPort = localPort;
        this.targetIp = targetIp;
        this.targetPort = targetPort;
        this.logger = logger;
        this.tcpCountUpdater = tcpCountUpdater;
        this.udpCountUpdater = udpCountUpdater;
    }

    public void start() throws IOException, BindException {
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

        logger.accept("Proxy service started on port " + localPort + " -> " + targetIp + ":" + targetPort);
    }

    public void stop() {
        logger.accept("Stopping proxy service...");
        try {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
            if (udpListener != null && !udpListener.isClosed()) udpListener.close();

            if (executorService != null) executorService.shutdownNow();
            if (scheduledExecutorService != null) scheduledExecutorService.shutdownNow();

            udpSessions.values().forEach(UdpSession::close);
            udpSessions.clear();
            activeTcpConnections.set(0);

            tcpCountUpdater.run();
            udpCountUpdater.run();
            logger.accept("Proxy service stopped.");
        } catch (IOException e) {
            logger.accept("Error stopping proxy service: " + e.getMessage());
        }
    }

    public int getActiveTcpConnections() {
        return activeTcpConnections.get();
    }

    public int getActiveUdpSessions() {
        return udpSessions.size();
    }

    private void acceptTcpConnections(String targetIp, int targetPort) {
        while (!Thread.currentThread().isInterrupted() && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                executorService.submit(() -> handleNewTcpConnection(clientSocket, targetIp, targetPort));
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    logger.accept("Error accepting TCP connection: " + e.getMessage());
                }
            }
        }
        logger.accept("TCP listener stopped.");
    }

    private void handleNewTcpConnection(Socket clientSocket, String targetIp, int targetPort) {
        activeTcpConnections.incrementAndGet();
        tcpCountUpdater.run();
        logger.accept("[TCP] Client connected: " + clientSocket.getRemoteSocketAddress());

        try {
            Socket targetSocket = new Socket(targetIp, targetPort);
            executorService.submit(() -> forwardData(clientSocket, targetSocket, "Client -> Target"));
            executorService.submit(() -> forwardData(targetSocket, clientSocket, "Target -> Client"));
        } catch (IOException e) {
            logger.accept("[TCP] Error connecting to target: " + e.getMessage());
            try {
                clientSocket.close();
            } catch (IOException ioException) { /* ignore */ }
            activeTcpConnections.decrementAndGet();
            tcpCountUpdater.run();
        }
    }

    private void forwardData(Socket source, Socket destination, String direction) {
        try (InputStream input = source.getInputStream(); OutputStream output = destination.getOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                logger.accept(String.format("[TCP] %s (%s): %d bytes", direction, source.getRemoteSocketAddress(), read));
            }
        } catch (IOException e) {
            // Connection closed or error
        } finally {
            try {
                if (!source.isClosed()) source.close();
                if (!destination.isClosed()) destination.close();
            } catch (IOException e) { /* Ignore */ }

            if (direction.contains("Client")) {
                activeTcpConnections.decrementAndGet();
                tcpCountUpdater.run();
                logger.accept("[TCP] Connection closed: " + source.getRemoteSocketAddress());
            }
        }
    }

    private void listenForUdpPackets(String targetIp, int targetPort) {
        byte[] buffer = new byte[65507];
        while (!Thread.currentThread().isInterrupted() && udpListener != null && !udpListener.isClosed()) {
            try {
                DatagramPacket clientPacket = new DatagramPacket(buffer, buffer.length);
                udpListener.receive(clientPacket);
                SocketAddress clientAddress = clientPacket.getSocketAddress();

                UdpSession session = udpSessions.computeIfAbsent(clientAddress, addr -> {
                    try {
                        UdpSession newSession = new UdpSession(clientAddress, targetIp, targetPort, udpListener, logger);
                        executorService.submit(newSession);
                        udpCountUpdater.run();
                        logger.accept("[UDP] New session for " + clientAddress);
                        return newSession;
                    } catch (SocketException e) {
                        logger.accept("Error creating UDP session: " + e.getMessage());
                        return null;
                    }
                });

                if (session != null) {
                    session.sendToServer(clientPacket);
                    logger.accept(String.format("[UDP] Client(%s) -> Target: %d bytes", clientAddress, clientPacket.getLength()));
                }
            } catch (IOException e) {
                if (!udpListener.isClosed()) {
                    logger.accept("Error in UDP listener: " + e.getMessage());
                }
            }
        }
        logger.accept("UDP listener stopped.");
    }

    private void cleanupUdpSessions() {
        long now = System.currentTimeMillis();
        udpSessions.entrySet().removeIf(entry -> {
            if (now - entry.getValue().getLastActivity() > 60000) {
                entry.getValue().close();
                logger.accept("[UDP] Session timed out for " + entry.getKey());
                udpCountUpdater.run();
                return true;
            }
            return false;
        });
    }

    private static class UdpSession implements Runnable {
        private final DatagramSocket serverFacingSocket;
        private final SocketAddress clientAddress;
        private final DatagramSocket mainUdpListener;
        private final InetSocketAddress targetAddress;
        private volatile long lastActivity;
        private final Consumer<String> logger;

        UdpSession(SocketAddress clientAddress, String targetIp, int targetPort, DatagramSocket mainUdpListener, Consumer<String> logger) throws SocketException {
            this.clientAddress = clientAddress;
            this.mainUdpListener = mainUdpListener;
            this.targetAddress = new InetSocketAddress(targetIp, targetPort);
            this.serverFacingSocket = new DatagramSocket();
            this.lastActivity = System.currentTimeMillis();
            this.logger = logger;
        }

        void sendToServer(DatagramPacket clientPacket) throws IOException {
            lastActivity = System.currentTimeMillis();
            DatagramPacket serverPacket = new DatagramPacket(clientPacket.getData(), clientPacket.getLength(), targetAddress);
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

                    DatagramPacket clientResponse = new DatagramPacket(serverResponse.getData(), serverResponse.getLength(), clientAddress);
                    mainUdpListener.send(clientResponse);
                    logger.accept(String.format("[UDP] Target -> Client(%s): %d bytes", clientAddress, serverResponse.getLength()));
                } catch (IOException e) {
                    if (!serverFacingSocket.isClosed()) {
                        logger.accept("[UDP] Error in session for " + clientAddress + ": " + e.getMessage());
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