package com.proxy.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DummyServer {
    public static void main(String[] args) throws IOException {
        int port = 5001;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        System.out.println("Starting dummy server on port " + port);

        ExecutorService executor = Executors.newCachedThreadPool();

        // TCP Server
        ServerSocket serverSocket = new ServerSocket(port);
        executor.submit(() -> {
            while (true) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("[TCP Server] Client connected: " + clientSocket.getRemoteSocketAddress());
                    executor.submit(() -> handleTcpClient(clientSocket));
                } catch (IOException e) {
                    System.err.println("[TCP Server] Error accepting connection: " + e.getMessage());
                }
            }
        });

        // UDP Server
        DatagramSocket datagramSocket = new DatagramSocket(port);
        executor.submit(() -> {
            byte[] buffer = new byte[1024];
            while (true) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    datagramSocket.receive(packet);
                    System.out.println("[UDP Server] Received from " + packet.getSocketAddress() + ": " + new String(packet.getData(), 0, packet.getLength()));

                    // Echo back
                    DatagramPacket response = new DatagramPacket(packet.getData(), packet.getLength(), packet.getSocketAddress());
                    datagramSocket.send(response);
                } catch (IOException e) {
                    System.err.println("[UDP Server] Error: " + e.getMessage());
                }
            }
        });
    }

    private static void handleTcpClient(Socket clientSocket) {
        try (InputStream in = clientSocket.getInputStream(); OutputStream out = clientSocket.getOutputStream()) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                String received = new String(buffer, 0, bytesRead);
                System.out.println("[TCP Server] Received from " + clientSocket.getRemoteSocketAddress() + ": " + received);

                // Echo back
                out.write(buffer, 0, bytesRead);
                out.flush();

                // Server push test
                if (received.trim().equals("PUSH")) {
                    for (int i = 0; i < 3; i++) {
                        Thread.sleep(1000);
                        String pushMessage = "Server push " + (i + 1);
                        System.out.println("[TCP Server] Pushing to " + clientSocket.getRemoteSocketAddress() + ": " + pushMessage);
                        out.write(pushMessage.getBytes());
                        out.flush();
                    }
                }
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("[TCP Server] Error handling client: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                // ignore
            }
            System.out.println("[TCP Server] Client disconnected: " + clientSocket.getRemoteSocketAddress());
        }
    }
}
