package com.proxy.test;

import com.proxy.ProxyService;
import com.proxy.TransformRule;
import org.junit.jupiter.api.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies rule-based transform (intercept) mode: a client request that exactly
 * matches an enabled rule's request gets that rule's response, without the
 * request reaching the target server. Non-matching requests are unaffected.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TransformProxyTest {

    private static final int PROXY_PORT = 26000;
    private static final int DEAD_TARGET_PORT = 26999; // nothing listens here
    private static final String IP = "127.0.0.1";

    private final List<TransformRule> rules = new ArrayList<>();
    private ProxyService proxyService;

    @BeforeAll
    void setup() throws Exception {
        proxyService = new ProxyService(PROXY_PORT, IP, DEAD_TARGET_PORT,
                System.out::println, () -> {}, () -> {}, packet -> {});
        proxyService.setTransformRulesSupplier(() -> rules);
        proxyService.start();
        Thread.sleep(500);
    }

    @AfterAll
    void teardown() {
        if (proxyService != null) proxyService.stop();
    }

    @BeforeEach
    void resetRules() {
        rules.clear();
    }

    @Test
    @DisplayName("TCP: matching request returns the rule's response, not a forwarded echo")
    void testTcpTransformMatch() throws Exception {
        byte[] request = "PING".getBytes(StandardCharsets.UTF_8);
        byte[] response = "PONG-TRANSFORMED".getBytes(StandardCharsets.UTF_8);
        rules.add(new TransformRule("TCP", request, response, true));

        try (Socket socket = new Socket(IP, PROXY_PORT)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write(request);
            out.flush();

            byte[] buffer = new byte[1024];
            int read = in.read(buffer);
            assertArrayEquals(response, java.util.Arrays.copyOf(buffer, read),
                    "Matching request should receive the rule response.");
            System.out.println("TCP Transform Match Test PASSED");
        }
    }

    @Test
    @DisplayName("TCP: a disabled rule does not intercept (request would be forwarded)")
    void testTcpDisabledRuleNoIntercept() throws Exception {
        byte[] request = "PING".getBytes(StandardCharsets.UTF_8);
        rules.add(new TransformRule("TCP", request, "SHOULD-NOT-SEND".getBytes(StandardCharsets.UTF_8), false));

        try (Socket socket = new Socket(IP, PROXY_PORT)) {
            socket.setSoTimeout(1200);
            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();

            byte[] buffer = new byte[1024];
            // Target is dead, so with no active rule there is no response -> timeout expected.
            assertThrows(SocketTimeoutException.class, () -> socket.getInputStream().read(buffer),
                    "Disabled rule must not intercept; nothing should be returned.");
            System.out.println("TCP Disabled Rule Test PASSED");
        }
    }

    @Test
    @DisplayName("UDP: matching request returns the rule's response")
    void testUdpTransformMatch() throws Exception {
        byte[] request = "UDP-PING".getBytes(StandardCharsets.UTF_8);
        byte[] response = "UDP-PONG".getBytes(StandardCharsets.UTF_8);
        rules.add(new TransformRule("UDP", request, response, true));

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(3000);
            socket.send(new DatagramPacket(request, request.length, InetAddress.getByName(IP), PROXY_PORT));

            byte[] buffer = new byte[1024];
            DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
            socket.receive(receivePacket);
            assertArrayEquals(response, java.util.Arrays.copyOf(receivePacket.getData(), receivePacket.getLength()),
                    "Matching UDP request should receive the rule response.");
            System.out.println("UDP Transform Match Test PASSED");
        }
    }

    @Test
    @DisplayName("Protocol is part of the match: a UDP rule does not fire for a TCP request")
    void testProtocolIsolation() throws Exception {
        byte[] request = "PING".getBytes(StandardCharsets.UTF_8);
        rules.add(new TransformRule("UDP", request, "UDP-ONLY".getBytes(StandardCharsets.UTF_8), true));

        try (Socket socket = new Socket(IP, PROXY_PORT)) {
            socket.setSoTimeout(1200);
            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();

            byte[] buffer = new byte[1024];
            assertThrows(SocketTimeoutException.class, () -> socket.getInputStream().read(buffer),
                    "A UDP rule must not intercept a TCP request.");
            System.out.println("Protocol Isolation Test PASSED");
        }
    }
}
