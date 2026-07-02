package com.proxy.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class DummyClient {

    private static final int PROXY_PORT = 5000; // Proxy port is hardcoded

    public static void main(String[] args) throws IOException, InterruptedException {
        System.out.println("--- Starting Automated TCP Test ---");
        runTcpTest(PROXY_PORT);
        System.out.println("--- TCP Test Finished ---\n");

        // Wait a moment for server-side resources to be potentially released
        Thread.sleep(2000);

        System.out.println("--- Starting Automated UDP Test ---");
        runUdpTest(PROXY_PORT);
        System.out.println("--- UDP Test Finished ---");
    }

    private static void runTcpTest(int port) throws IOException, InterruptedException {
        try (Socket socket = new Socket("127.0.0.1", port);
             InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {

            System.out.println("Connected to proxy via TCP on port " + port);
            socket.setSoTimeout(5000); // Set a timeout to prevent the test from hanging

            Thread readerThread = new Thread(() -> {
                try {
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        String received = new String(buffer, 0, bytesRead);
                        System.out.println("Received from server: " + received);
                    }
                } catch (SocketTimeoutException e) {
                    System.out.println("Reader thread timed out as expected after server push.");
                } catch (IOException e) {
                    // This is expected if the main thread closes the socket
                }
            });
            readerThread.start();

            // Test Scenario 1 & 2: Send a message and get an echo
            String echoMessage = "Hello TCP Echo";
            System.out.println("Sending for echo: " + echoMessage);
            out.write(echoMessage.getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(1000); // Wait for the echo to be received and printed

            // Test Scenario 3: Trigger server push
            String pushMessage = "PUSH";
            System.out.println("Sending to trigger server push: " + pushMessage);
            out.write(pushMessage.getBytes(StandardCharsets.UTF_8));
            out.flush();

            // Wait for the reader thread to receive all pushed messages and time out
            readerThread.join(8000);
        }
        System.out.println("TCP client socket closed.");
    }

    private static void runUdpTest(int port) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress address = InetAddress.getByName("127.0.0.1");
            socket.setSoTimeout(3000); // Set a 3-second timeout for receiving

            System.out.println("UDP client ready for port " + port);

            // Test Scenario 1 & 2: Send a message and get an echo
            String message = "Hello UDP Echo";
            byte[] sendData = message.getBytes(StandardCharsets.UTF_8);
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, address, port);

            System.out.println("Sending: " + message);
            socket.send(sendPacket);

            try {
                byte[] receiveBuffer = new byte[1024];
                DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                socket.receive(receivePacket);
                String received = new String(receivePacket.getData(), 0, receivePacket.getLength());
                System.out.println("Received from server: " + received);
            } catch (SocketTimeoutException e) {
                System.err.println("UDP response timed out. The proxy or server may not be running.");
            }
        }
        System.out.println("UDP client socket closed.");
    }
}