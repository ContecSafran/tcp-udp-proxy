package com.proxy.test;

import com.proxy.ProxyService;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ProxyTest {

    private static final int PROXY_PORT = 25000;
    private static final int SERVER_PORT = 25001;
    private static final String SERVER_IP = "127.0.0.1";

    private ExecutorService serverExecutor;
    private ProxyService proxyService;

    @BeforeAll
    void setup() throws Exception {
        System.out.println("--- Setting up tests ---");

        // 1. Start Dummy Server in the background
        serverExecutor = Executors.newSingleThreadExecutor();
        serverExecutor.submit(() -> {
            try {
                System.out.println("Starting DummyServer on port " + SERVER_PORT);
                DummyServer.main(new String[]{String.valueOf(SERVER_PORT)});
            } catch (IOException e) {
                // This will cause tests to fail, which is intended.
                System.err.println("Failed to start DummyServer: " + e.getMessage());
            }
        });

        // 2. Start ProxyService programmatically
        // UI-related callbacks are replaced with empty lambdas or simple console logs.
        proxyService = new ProxyService(PROXY_PORT, SERVER_IP, SERVER_PORT,
                System.out::println, // Logger
                () -> {},           // TCP count updater
                () -> {}            // UDP count updater
        );

        try {
            System.out.println("Starting ProxyService: " + PROXY_PORT + " -> " + SERVER_PORT);
            proxyService.start();
        } catch (IOException e) {
            fail("ProxyService could not be started: " + e.getMessage());
        }

        // Give both servers a moment to initialize before running tests
        Thread.sleep(1000);
        System.out.println("--- Setup complete, running tests ---");
    }

    @AfterAll
    void teardown() {
        System.out.println("--- Tearing down tests ---");
        if (proxyService != null) {
            proxyService.stop();
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
        System.out.println("--- Teardown complete ---");
    }

    @Test
    @DisplayName("TCP: Should receive an echo for a sent message")
    void testTcpEcho() {
        try (Socket socket = new Socket(SERVER_IP, PROXY_PORT)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            String message = "tcp-echo-test";
            out.write(message.getBytes(StandardCharsets.UTF_8));

            byte[] buffer = new byte[1024];
            int bytesRead = in.read(buffer);
            String response = new String(buffer, 0, bytesRead);

            assertEquals(message, response, "Server should echo the exact message.");
            System.out.println("TCP Echo Test PASSED");

        } catch (IOException e) {
            fail("TCP Echo Test FAILED: " + e.getMessage(), e);
        }
    }

    @Test
    @DisplayName("TCP: Should receive multiple server pushes")
    void testTcpServerPush() {
        try (Socket socket = new Socket(SERVER_IP, PROXY_PORT)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write("PUSH".getBytes(StandardCharsets.UTF_8));

            List<String> receivedMessages = new ArrayList<>();
            byte[] buffer = new byte[1024];
            long startTime = System.currentTimeMillis();

            // The server sends 3 pushes. We read them.
            while (System.currentTimeMillis() - startTime < 4000 && receivedMessages.size() < 3) {
                try {
                    int bytesRead = in.read(buffer);
                    if (bytesRead == -1) break;
                    // The server's response might be "Received... PUSHServer push X"
                    // So we check if the response contains the expected push message
                    String response = new String(buffer, 0, bytesRead);
                    if (response.contains("Server push")) {
                        receivedMessages.add(response);
                    }
                } catch (SocketTimeoutException ex) {
                    // This can happen if pushes are slow, we just retry reading.
                }
            }
            
            // Adjusting assertions to be more robust
            assertEquals(3, receivedMessages.size(), "Should receive 3 push messages.");
            assertTrue(receivedMessages.stream().anyMatch(s -> s.contains("Server push 1")), "Push 1 missing");
            assertTrue(receivedMessages.stream().anyMatch(s -> s.contains("Server push 2")), "Push 2 missing");
            assertTrue(receivedMessages.stream().anyMatch(s -> s.contains("Server push 3")), "Push 3 missing");

            System.out.println("TCP Server Push Test PASSED");

        } catch (IOException e) {
            fail("TCP Server Push Test FAILED: " + e.getMessage(), e);
        }
    }

    @Test
    @DisplayName("UDP: Should receive an echo for a sent packet")
    void testUdpEcho() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(3000);
            String message = "udp-echo-test";
            byte[] sendData = message.getBytes(StandardCharsets.UTF_8);
            InetAddress address = InetAddress.getByName(SERVER_IP);
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, address, PROXY_PORT);

            socket.send(sendPacket);

            byte[] receiveBuffer = new byte[1024];
            DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            socket.receive(receivePacket);

            String response = new String(receivePacket.getData(), 0, receivePacket.getLength());

            assertEquals(message, response, "Server should echo the exact UDP packet.");
            System.out.println("UDP Echo Test PASSED");

        } catch (IOException e) {
            fail("UDP Echo Test FAILED: " + e.getMessage(), e);
        }
    }
}