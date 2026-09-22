package com.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ProxyService {

    private final int localPort;
    private final String targetIp;
    private final int targetPort;

    private ExecutorService executorService;
    private ScheduledExecutorService scheduledExecutorService;
    private ServerSocket serverSocket;
    private DatagramSocket udpListener;

    private final Map<SocketAddress, UdpSession> udpSessions = new ConcurrentHashMap<>();
    /** Live TCP sockets (client and target side) so {@link #stop()} can close them. */
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private final AtomicInteger activeTcpConnections = new AtomicInteger(0);

    // Callbacks for logging and UI updates
    private final Consumer<String> logger;
    private final Runnable tcpCountUpdater;
    private final Runnable udpCountUpdater;
    private final Consumer<PacketInfo> packetListener;

    // Transform (intercept) rules. When an incoming client request matches an
    // enabled rule's request, the proxy replies with that rule's response
    // instead of forwarding the request to the target server.
    private volatile Supplier<List<TransformRule>> transformRulesSupplier = Collections::emptyList;

    public void setTransformRulesSupplier(Supplier<List<TransformRule>> supplier) {
        this.transformRulesSupplier = supplier != null ? supplier : Collections::emptyList;
    }

    /** Returns the first enabled rule whose request matches, or null if none. */
    private TransformRule matchRule(String protocol, byte[] request) {
        for (TransformRule rule : transformRulesSupplier.get()) {
            if (rule.matches(protocol, request)) {
                return rule;
            }
        }
        return null;
    }

    public ProxyService(int localPort, String targetIp, int targetPort, Consumer<String> logger, Runnable tcpCountUpdater, Runnable udpCountUpdater) {
        this(localPort, targetIp, targetPort, logger, tcpCountUpdater, udpCountUpdater, packet -> { });
    }

    public ProxyService(int localPort, String targetIp, int targetPort, Consumer<String> logger, Runnable tcpCountUpdater, Runnable udpCountUpdater, Consumer<PacketInfo> packetListener) {
        this.localPort = localPort;
        this.targetIp = targetIp;
        this.targetPort = targetPort;
        this.logger = logger;
        this.tcpCountUpdater = tcpCountUpdater;
        this.udpCountUpdater = udpCountUpdater;
        this.packetListener = packetListener;
    }

    public void start() throws IOException, BindException {
        executorService = Executors.newCachedThreadPool(ProxyService::worker);
        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(ProxyService::worker);

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

    /** Worker threads are daemons so a leftover one can never keep the JVM alive. */
    private static Thread worker(Runnable runnable) {
        Thread thread = new Thread(runnable, "proxy-worker");
        thread.setDaemon(true);
        return thread;
    }

    public void stop() {
        logger.accept("Stopping proxy service...");

        // Stop accepting new traffic first.
        closeQuietly(serverSocket);
        closeQuietly(udpListener);

        // Then close every live connection. This is what actually releases the
        // sockets: the reader threads sit in a blocking InputStream.read(), which
        // does not react to Thread.interrupt(), so shutdownNow() on its own would
        // leave both the threads and the connections alive.
        int closed = activeSockets.size();
        for (Socket socket : activeSockets) {
            closeQuietly(socket);
        }
        activeSockets.clear();

        udpSessions.values().forEach(UdpSession::close);
        udpSessions.clear();

        if (executorService != null) executorService.shutdownNow();
        if (scheduledExecutorService != null) scheduledExecutorService.shutdownNow();

        activeTcpConnections.set(0);
        tcpCountUpdater.run();
        udpCountUpdater.run();
        logger.accept("Proxy service stopped. (closed " + closed + " open socket(s))");
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
        activeSockets.add(clientSocket);
        tcpCountUpdater.run();
        logger.accept("[TCP] Client connected: " + clientSocket.getRemoteSocketAddress());

        // Connect to the target for traffic that no rule intercepts. If the target
        // is unreachable we still serve matching rules (intercept-only).
        Socket targetSocket = null;
        try {
            targetSocket = new Socket(targetIp, targetPort);
        } catch (IOException e) {
            logger.accept("[TCP] Target unreachable, intercept-only mode: " + e.getMessage());
        }

        final Socket target = targetSocket;
        if (target != null) {
            activeSockets.add(target);
            executorService.submit(() -> pumpTargetToClient(target, clientSocket));
        }
        // The client-reading task owns the connection lifecycle (and the counter).
        executorService.submit(() -> interceptOrForwardClient(clientSocket, target));
    }

    /**
     * Reads each chunk from the client. If it matches an enabled transform rule,
     * the rule's response is written straight back to the client; otherwise the
     * chunk is forwarded to the target (or dropped if there is no target).
     */
    private void interceptOrForwardClient(Socket clientSocket, Socket targetSocket) {
        String remote = String.valueOf(clientSocket.getRemoteSocketAddress());
        try {
            InputStream input = clientSocket.getInputStream();
            OutputStream toClient = clientSocket.getOutputStream();
            OutputStream toTarget = targetSocket != null ? targetSocket.getOutputStream() : null;

            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                byte[] request = Arrays.copyOf(buffer, read);
                packetListener.accept(new PacketInfo("TCP", "Client -> Target", remote, request));

                TransformRule rule = matchRule("TCP", request);
                if (rule != null) {
                    byte[] response = rule.getResponse();
                    toClient.write(response);
                    toClient.flush();
                    logger.accept(String.format("[TCP][Transform] Client(%s) matched rule -> response %d bytes", remote, response.length));
                    packetListener.accept(new PacketInfo("TCP", "Proxy -> Client (transform)", remote, Arrays.copyOf(response, response.length)));
                } else if (toTarget != null) {
                    toTarget.write(buffer, 0, read);
                    toTarget.flush();
                    logger.accept(String.format("[TCP] Client -> Target (%s): %d bytes", remote, read));
                } else {
                    logger.accept(String.format("[TCP] No matching rule and no target for %s; dropped %d bytes", remote, read));
                }
            }
        } catch (IOException e) {
            // Connection closed or error
        } finally {
            closeQuietly(clientSocket);
            closeQuietly(targetSocket);
            // Clamped: stop() zeroes the counter, so a connection torn down by it
            // must not push the count negative.
            activeTcpConnections.updateAndGet(count -> Math.max(0, count - 1));
            tcpCountUpdater.run();
            logger.accept("[TCP] Connection closed: " + remote);
        }
    }

    /** Forwards target responses back to the client (for non-intercepted traffic). */
    private void pumpTargetToClient(Socket targetSocket, Socket clientSocket) {
        String remote = String.valueOf(clientSocket.getRemoteSocketAddress());
        try {
            InputStream input = targetSocket.getInputStream();
            OutputStream output = clientSocket.getOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                output.flush();
                logger.accept(String.format("[TCP] Target -> Client (%s): %d bytes", remote, read));
                packetListener.accept(new PacketInfo("TCP", "Target -> Client", remote, Arrays.copyOf(buffer, read)));
            }
        } catch (IOException e) {
            // Connection closed or error
        } finally {
            closeQuietly(targetSocket);
            closeQuietly(clientSocket);
        }
    }

    /** Closes a TCP socket and drops it from the live-socket set. */
    private void closeQuietly(Socket socket) {
        if (socket == null) return;
        activeSockets.remove(socket);
        if (!socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) { /* ignore */ }
        }
    }

    private void closeQuietly(ServerSocket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                logger.accept("Error closing TCP listener: " + e.getMessage());
            }
        }
    }

    private static void closeQuietly(DatagramSocket socket) {
        if (socket != null && !socket.isClosed()) {
            socket.close(); // DatagramSocket.close() does not throw
        }
    }

    private void listenForUdpPackets(String targetIp, int targetPort) {
        byte[] buffer = new byte[65507];
        while (!Thread.currentThread().isInterrupted() && udpListener != null && !udpListener.isClosed()) {
            try {
                DatagramPacket clientPacket = new DatagramPacket(buffer, buffer.length);
                udpListener.receive(clientPacket);
                SocketAddress clientAddress = clientPacket.getSocketAddress();
                byte[] request = Arrays.copyOf(clientPacket.getData(), clientPacket.getLength());

                packetListener.accept(new PacketInfo("UDP", "Client -> Target", String.valueOf(clientAddress), request));

                TransformRule rule = matchRule("UDP", request);
                if (rule != null) {
                    byte[] response = rule.getResponse();
                    udpListener.send(new DatagramPacket(response, response.length, clientPacket.getSocketAddress()));
                    logger.accept(String.format("[UDP][Transform] Client(%s) matched rule -> response %d bytes", clientAddress, response.length));
                    packetListener.accept(new PacketInfo("UDP", "Proxy -> Client (transform)", String.valueOf(clientAddress), Arrays.copyOf(response, response.length)));
                    continue;
                }

                UdpSession session = udpSessions.computeIfAbsent(clientAddress, addr -> {
                    try {
                        UdpSession newSession = new UdpSession(clientAddress, targetIp, targetPort, udpListener, logger, packetListener);
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
        private final Consumer<PacketInfo> packetListener;

        UdpSession(SocketAddress clientAddress, String targetIp, int targetPort, DatagramSocket mainUdpListener, Consumer<String> logger, Consumer<PacketInfo> packetListener) throws SocketException {
            this.clientAddress = clientAddress;
            this.mainUdpListener = mainUdpListener;
            this.targetAddress = new InetSocketAddress(targetIp, targetPort);
            this.serverFacingSocket = new DatagramSocket();
            this.lastActivity = System.currentTimeMillis();
            this.logger = logger;
            this.packetListener = packetListener;
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
                    packetListener.accept(new PacketInfo("UDP", "Target -> Client", String.valueOf(clientAddress),
                            Arrays.copyOf(serverResponse.getData(), serverResponse.getLength())));
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