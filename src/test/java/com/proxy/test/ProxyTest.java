package com.proxy.test;

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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ProxyTest {

    private static final int PROXY_PORT = 8080;
    private static final int SERVER_PORT = 8000;
    private static final String SERVER_IP = "127.0.0.1";

    private ExecutorService serverExecutor;
    private com.proxy.ProxyApp proxyApp; // Assuming ProxyApp is in com.proxy package

    @BeforeAll
    void setup() throws InterruptedException {
        // Start Dummy Server
        serverExecutor = Executors.newSingleThreadExecutor();
        serverExecutor.submit(() -> {
            try {
                DummyServer.main(new String[]{String.valueOf(SERVER_PORT)});
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        Thread.sleep(1000); // Give server time to start

        // Start Proxy
        proxyApp = new com.proxy.ProxyApp();
        // This part is tricky as ProxyApp is a Swing app.
        // For a real test, you'd refactor ProxyApp to separate logic from GUI.
        // Here, we'll simulate the start button click action.
        // This is a simplified approach.
        // proxyApp.startProxy(); // This would block if not handled correctly.
        // Let's assume we can't easily start the proxy programmatically.
        // The test will require manual setup:
        // 1. Run DummyServer.
        // 2. Run ProxyApp, set ports (8080 -> 8000), and click Start.
        // 3. Run this JUnit test.
        System.out.println("-----------------------------------------------------------------");
        System.out.println("IMPORTANT: Ensure DummyServer is running on port " + SERVER_PORT);
        System.out.println("AND ProxyApp is running, forwarding port " + PROXY_PORT + " to " + SERVER_PORT);
        System.out.println("-----------------------------------------------------------------");
    }

    @AfterAll
    void teardown() {
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
        // if (proxyApp != null) {
        //     proxyApp.stopProxy();
        // }
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
            fail("TCP Echo Test FAILED: " + e.getMessage());
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
                    receivedMessages.add(new String(buffer, 0, bytesRead));
                } catch (SocketTimeoutException ex) {
                    // This can happen if pushes are slow, we just retry reading.
                }
            }

            assertAll("Server Push Validation",
                () -> assertEquals(3, receivedMessages.size(), "Should receive 3 push messages."),
                () -> assertTrue(receivedMessages.get(0).contains("Server push 1")),
                () -> assertTrue(receivedMessages.get(1).contains("Server push 2")),
                () -> assertTrue(receivedMessages.get(2).contains("Server push 3"))
            );
            System.out.println("TCP Server Push Test PASSED");

        } catch (IOException e) {
            fail("TCP Server Push Test FAILED: " + e.getMessage());
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
            fail("UDP Echo Test FAILED: " + e.getMessage());
        }
    }
}