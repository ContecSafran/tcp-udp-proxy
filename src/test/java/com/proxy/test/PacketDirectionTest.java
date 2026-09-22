package com.proxy.test;

import com.proxy.PacketInfo;
import com.proxy.ProxyService;
import com.proxy.TransformRule;
import org.junit.jupiter.api.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The "→ 변환 규칙" button keys off {@link PacketInfo#isToClient()} /
 * {@link PacketInfo#isFromClient()}, which classify direction strings produced
 * in ProxyService. These tests pin that agreement: a typo on either side would
 * otherwise silently disable the button or pair the wrong packets.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PacketDirectionTest {

    private static final int PROXY_PORT = 26100;
    private static final int SERVER_PORT = 26101;
    private static final String IP = "127.0.0.1";

    private final List<PacketInfo> captured = new CopyOnWriteArrayList<>();
    private final List<TransformRule> rules = new ArrayList<>();
    private ExecutorService serverExecutor;
    private ProxyService proxyService;

    @BeforeAll
    void setup() throws Exception {
        serverExecutor = Executors.newSingleThreadExecutor();
        serverExecutor.submit(() -> {
            try {
                DummyServer.main(new String[]{String.valueOf(SERVER_PORT)});
            } catch (Exception e) {
                System.err.println("DummyServer failed: " + e.getMessage());
            }
        });

        proxyService = new ProxyService(PROXY_PORT, IP, SERVER_PORT,
                System.out::println, () -> { }, () -> { }, captured::add);
        proxyService.setTransformRulesSupplier(() -> rules);
        proxyService.start();
        Thread.sleep(500);
    }

    @AfterAll
    void teardown() {
        if (proxyService != null) proxyService.stop();
        if (serverExecutor != null) serverExecutor.shutdownNow();
    }

    @BeforeEach
    void reset() {
        captured.clear();
        rules.clear();
    }

    @Test
    @DisplayName("forwarded traffic is classified as request then response")
    void classifiesForwardedTraffic() throws Exception {
        try (Socket socket = new Socket(IP, PROXY_PORT)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            out.write("HELLO\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[1024];
            assertTrue(in.read(buffer) > 0, "target should answer");
        }
        Thread.sleep(300);

        PacketInfo request = captured.get(0);
        assertTrue(request.isFromClient(), "first packet should be a client request: " + request.getDirection());
        assertFalse(request.isToClient());

        PacketInfo response = captured.stream().filter(PacketInfo::isToClient).findFirst()
                .orElseThrow(() -> new AssertionError("no response packet captured: " + captured));
        assertFalse(response.isFromClient());
        // The pairing the button relies on: same protocol and same peer.
        assertEquals(request.getProtocol(), response.getProtocol());
        assertEquals(request.getRemoteAddress(), response.getRemoteAddress());
    }

    @Test
    @DisplayName("a transform reply also counts as a response packet")
    void classifiesTransformReply() throws Exception {
        byte[] request = "PING".getBytes(StandardCharsets.UTF_8);
        rules.add(new TransformRule("TCP", request, "PONG".getBytes(StandardCharsets.UTF_8), true));

        try (Socket socket = new Socket(IP, PROXY_PORT)) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();
            byte[] buffer = new byte[1024];
            assertTrue(socket.getInputStream().read(buffer) > 0);
        }
        Thread.sleep(300);

        PacketInfo transformReply = captured.stream()
                .filter(p -> p.getDirection().startsWith("Proxy -> Client"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no transform reply captured: " + captured));
        assertTrue(transformReply.isToClient(),
                "a transform reply must qualify for rule creation: " + transformReply.getDirection());
        assertFalse(transformReply.isFromClient());
    }
}
