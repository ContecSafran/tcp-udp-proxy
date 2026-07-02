package com.proxy.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class DummyClient {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.print("Enter proxy port: ");
        int port = Integer.parseInt(scanner.nextLine());
        System.out.print("Enter protocol (TCP/UDP): ");
        String protocol = scanner.nextLine().toUpperCase();

        try {
            if ("TCP".equals(protocol)) {
                runTcpTest(port, scanner);
            } else if ("UDP".equals(protocol)) {
                runUdpTest(port, scanner);
            } else {
                System.out.println("Invalid protocol.");
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Client error: " + e.getMessage());
        }
    }

    private static void runTcpTest(int port, Scanner scanner) throws IOException, InterruptedException {
        try (Socket socket = new Socket("127.0.0.1", port);
             InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {

            System.out.println("Connected to proxy via TCP. Type messages to send, 'PUSH' for server push test, or 'exit' to quit.");

            Thread readerThread = new Thread(() -> {
                try {
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        System.out.println("Received from server: " + new String(buffer, 0, bytesRead));
                    }
                } catch (IOException e) {
                    if (!socket.isClosed()) {
                        System.err.println("Connection error: " + e.getMessage());
                    }
                }
            });
            readerThread.start();

            while (true) {
                String line = scanner.nextLine();
                if ("exit".equalsIgnoreCase(line)) {
                    break;
                }
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    private static void runUdpTest(int port, Scanner scanner) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress address = InetAddress.getByName("127.0.0.1");
            System.out.println("UDP client started. Type messages to send or 'exit' to quit.");

            Thread readerThread = new Thread(() -> {
                try {
                    byte[] buffer = new byte[1024];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    while (!socket.isClosed()) {
                        socket.receive(packet);
                        System.out.println("Received from server: " + new String(packet.getData(), 0, packet.getLength()));
                    }
                } catch (IOException e) {
                    if (!socket.isClosed()) {
                        System.err.println("Socket error: " + e.getMessage());
                    }
                }
            });
            readerThread.start();

            while (true) {
                String line = scanner.nextLine();
                if ("exit".equalsIgnoreCase(line)) {
                    break;
                }
                byte[] data = line.getBytes(StandardCharsets.UTF_8);
                DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
                socket.send(packet);
            }
        }
    }
}
