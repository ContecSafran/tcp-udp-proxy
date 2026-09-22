package com.proxy.test;

import com.proxy.ProxyService;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that stop() really releases the sockets: the listening port must be
 * free again and connections that were already established must be torn down.
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
public class ProxyStopTest {

    private static final int PROXY_PORT = 25100;
    private static final int SERVER_PORT = 25101;
    private static final String SERVER_IP = "127.0.0.1";

    private ExecutorService serverExecutor;
    private ProxyService proxyService;

    @BeforeEach
    void setup() throws Exception {
        serverExecutor = Executors.newSingleThreadExecutor();
        serverExecutor.submit(() -> {
            try {
                DummyServer.main(new String[]{String.valueOf(SERVER_PORT)});
            } catch (IOException e) {
                System.err.println("Failed to start DummyServer: " + e.getMessage());
            }
        });

        proxyService = new ProxyService(PROXY_PORT, SERVER_IP, SERVER_PORT,
                System.out::println, () -> { }, () -> { });
        proxyService.start();
        Thread.sleep(500);
    }

    @AfterEach
    void tearDown() {
        if (proxyService != null) proxyService.stop();
        if (serverExecutor != null) serverExecutor.shutdownNow();
    }

    @Test
    @DisplayName("stop() closes an already established client connection")
    void stopClosesEstablishedConnection() throws Exception {
        try (Socket client = new Socket(SERVER_IP, PROXY_PORT)) {
            client.setSoTimeout(5000);
            OutputStream out = client.getOutputStream();
            out.write("hello\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = client.getInputStream();
            assertTrue(in.read() != -1, "proxy should forward traffic while running");

            proxyService.stop();

            // The client side must now see EOF (or a reset) rather than hanging:
            // before the fix the reader threads stayed blocked and the socket
            // remained open, so this read would time out.
            long deadline = System.currentTimeMillis() + 5000;
            boolean closed = false;
            while (System.currentTimeMillis() < deadline) {
                try {
                    if (in.read() == -1) {
                        closed = true;
                        break;
                    }
                } catch (SocketException e) { // connection reset — also a close
                    closed = true;
                    break;
                }
            }
            assertTrue(closed, "stop() must tear down established connections");
        }
    }

    @Test
    @DisplayName("stop() frees the listening port so the proxy can restart")
    void stopFreesListeningPort() throws Exception {
        try (Socket ignored = new Socket(SERVER_IP, PROXY_PORT)) {
            assertTrue(ignored.isConnected());
        }

        proxyService.stop();

        // Both the TCP and the UDP listener must be gone: rebinding proves it.
        try (ServerSocket tcp = new ServerSocket(PROXY_PORT);
             DatagramSocket udp = new DatagramSocket(PROXY_PORT)) {
            assertTrue(tcp.isBound());
            assertTrue(udp.isBound());
        }
    }

    @Test
    @DisplayName("proxy can be started again after stop()")
    void restartAfterStop() throws Exception {
        proxyService.stop();

        ProxyService restarted = new ProxyService(PROXY_PORT, SERVER_IP, SERVER_PORT,
                System.out::println, () -> { }, () -> { });
        try {
            assertDoesNotThrow(restarted::start, "port must be free after stop()");
            try (Socket client = new Socket(SERVER_IP, PROXY_PORT)) {
                assertTrue(client.isConnected());
            }
        } finally {
            restarted.stop();
        }
    }
}
