package io.github.stormino.megatron.http;

import io.github.stormino.megatron.proxy.ProxyInfo;
import io.github.stormino.megatron.proxy.ProxyProtocol;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class Socks4SocketFactoryTest {

    FakeSocks4Server socks;
    MockWebServer web;

    @BeforeEach
    void setUp() throws IOException {
        socks = new FakeSocks4Server();
        web = new MockWebServer();
        web.start(InetAddress.getLoopbackAddress(), 0);
    }

    @AfterEach
    void tearDown() throws IOException {
        socks.close();
        web.shutdown();
    }

    private OkHttpClient client() {
        ProxyInfo proxy = new ProxyInfo("127.0.0.1", socks.port(), ProxyProtocol.SOCKS4);
        return new HttpClientFactory().forProxy(proxy, Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    @Test
    void requestBytesForIpv4AndSocks4a() throws Exception {
        byte[] v4 = Socks4SocketFactory.buildRequest(
                new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 1, 2, 3}), 443), "me");
        assertArrayEquals(new byte[]{4, 1, 1, (byte) 0xBB, 10, 1, 2, 3, 'm', 'e', 0}, v4);

        byte[] v4a = Socks4SocketFactory.buildRequest(InetSocketAddress.createUnresolved("ab.c", 80), "");
        assertArrayEquals(new byte[]{4, 1, 0, 80, 0, 0, 0, 1, 0, 'a', 'b', '.', 'c', 0}, v4a);

        assertThrows(SocketException.class, () -> Socks4SocketFactory.buildRequest(
                new InetSocketAddress(InetAddress.getByName("::1"), 80), ""));
    }

    @Test
    void httpRequestThroughSocks4() throws Exception {
        web.enqueue(new MockResponse().setBody("hello through socks4"));
        Request req = new Request.Builder().url(web.url("/x")).build();
        try (Response resp = client().newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("hello through socks4", resp.body().string());
        }
        FakeSocks4Server.Request r = socks.requests.get(0);
        assertEquals(4, r.version());
        assertEquals(1, r.command());
        assertEquals("127.0.0.1", r.ip());
        assertEquals(web.getPort(), r.port());
        assertNull(r.host());
        // The origin saw a normal origin-form request, not a proxy request.
        assertEquals("/x", web.takeRequest().getPath());
    }

    @Test
    void refusedConnectionFails() {
        socks.replyStatus = 0x5B;
        Request req = new Request.Builder().url(web.url("/x")).build();
        IOException e = assertThrows(IOException.class, () -> client().newCall(req).execute().close());
        assertTrue(String.valueOf(e.getMessage()).contains("refused")
                || (e.getCause() != null && String.valueOf(e.getCause().getMessage()).contains("refused")), e::toString);
    }

    @Test
    void socks4aHandsTheHostNameToTheProxy() throws Exception {
        web.enqueue(new MockResponse().setBody("x"));
        var factory = new Socks4SocketFactory(new InetSocketAddress("127.0.0.1", socks.port()), "");
        try (Socket s = factory.createSocket()) {
            s.connect(InetSocketAddress.createUnresolved("localhost", web.getPort()), 5000);
            s.setSoTimeout(5000);
            s.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes());
            // Read the status line only: the origin keeps the connection alive.
            var reader = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream()));
            String status = reader.readLine();
            assertTrue(status.startsWith("HTTP/1.1 200"), status);
        }
        assertEquals("localhost", socks.requests.get(0).host());
    }

    @Test
    void silentProxyTimesOut() throws Exception {
        try (var silent = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var factory = new Socks4SocketFactory(new InetSocketAddress("127.0.0.1", silent.getLocalPort()), "");
            Socket s = factory.createSocket();
            long t0 = System.nanoTime();
            assertThrows(IOException.class, () -> s.connect(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 80), 300));
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 3000, "handshake must honour the connect timeout");
            assertTrue(s.isClosed());
        }
    }
}
