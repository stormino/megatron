package io.github.stormino.megatron.proxy;

import io.github.stormino.megatron.http.HttpClientFactory;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Timeout(60)
class ProxyValidatorTest {

    private final List<MockWebServer> servers = new ArrayList<>();

    /** An HTTP "proxy" that answers every check itself, reporting {@code exitIp} as the caller's IP. */
    private ProxyInfo proxy(String exitIp, boolean reachesMega) throws IOException {
        MockWebServer s = new MockWebServer();
        s.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                String line = r.getRequestLine();
                if (line.contains("alive.test")) {
                    return new MockResponse().setResponseCode(204);
                }
                if (line.contains("mega.test")) {
                    return reachesMega ? new MockResponse().setBody("-2")
                            : new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST);
                }
                if (line.contains("ip.test")) {
                    return exitIp == null ? new MockResponse().setBody("<html>blocked</html>")
                            : new MockResponse().setBody(exitIp + "\n");
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        s.start();
        servers.add(s);
        return new ProxyInfo(s.getHostName(), s.getPort(), ProxyProtocol.HTTP);
    }

    @AfterEach
    void tearDown() throws IOException {
        for (MockWebServer s : servers) {
            s.shutdown();
        }
    }

    private ProxyValidator validator(int targetAlive) {
        var settings = ProxyValidator.Settings.defaults()
                .withUrls("http://alive.test/generate_204", "http://mega.test/cs", "http://ip.test/")
                .withTargetAlive(targetAlive);
        ProxyValidator v = new ProxyValidator(new HttpClientFactory(), settings);
        v.ownIp("9.9.9.9");
        return v;
    }

    @Test
    void recordsExitIpsAndDropsTransparentProxies() throws Exception {
        ProxyInfo a = proxy("1.1.1.1", true);
        ProxyInfo b = proxy("1.1.1.1", true);
        ProxyInfo c = proxy("2.2.2.2", true);
        ProxyInfo transparent = proxy("9.9.9.9", true);
        ProxyInfo noEcho = proxy(null, true);
        ProxyInfo noMega = proxy("3.3.3.3", false);

        List<ProxyInfo> ok = validator(0).validate(List.of(a, b, c, transparent, noEcho, noMega));

        assertEquals(java.util.Set.of(a, b, c, noEcho), new java.util.HashSet<>(ok));
        assertEquals("1.1.1.1", a.exitIp());
        assertEquals("2.2.2.2", c.exitIp());
        assertNull(noEcho.exitIp(), "garbage from the echo service is not an IP");
        Map<String, Long> byExit = ok.stream().collect(
                java.util.stream.Collectors.groupingBy(ProxyInfo::exitKey, java.util.stream.Collectors.counting()));
        assertEquals(3, byExit.size(), "a and b share one exit; noEcho is its own group");
    }

    @Test
    void earlyStopCountsDistinctExitsNotProxies() throws Exception {
        List<ProxyInfo> cluster = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            cluster.add(proxy("5.5.5.5", true));
        }
        List<ProxyInfo> all = new ArrayList<>(cluster);
        all.add(proxy("6.6.6.6", true));
        // Target 2 exits: four proxies behind 5.5.5.5 alone must not satisfy it.
        List<ProxyInfo> ok = validator(2).validate(all);
        assertEquals(2, ok.stream().map(ProxyInfo::exitKey).distinct().count());
    }
}
