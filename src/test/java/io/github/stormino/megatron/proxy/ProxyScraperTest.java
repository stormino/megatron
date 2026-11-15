package io.github.stormino.megatron.proxy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProxyScraperTest {

    private static List<ProxyInfo> parse(SourceKind kind, String body) {
        return ProxyScraper.parse(new ProxySource("t", "http://x", kind, ProxyProtocol.SOCKS5), body);
    }

    @Test
    void plainText() {
        List<ProxyInfo> out = parse(SourceKind.PLAIN_TEXT, """
                # comment
                1.2.3.4:8080
                socks5://5.6.7.8:1080
                http://9.9.9.9:3128/extra
                garbage
                host.example:99999
                """);
        assertEquals(List.of(ProxyInfo.parse("1.2.3.4:8080"), ProxyInfo.parse("5.6.7.8:1080"),
                ProxyInfo.parse("9.9.9.9:3128")), out);
        // The source protocol wins over the line prefix.
        out.forEach(p -> assertEquals(ProxyProtocol.SOCKS5, p.protocol()));
    }

    @Test
    void htmlTable() {
        List<ProxyInfo> out = parse(SourceKind.HTML_TABLE, """
                <table><tr><th>IP</th><th>Port</th></tr>
                <tr><td>1.1.1.1</td><td>80</td><td>US</td></tr>
                <tr><td>2.2.2.2</td><td>x</td></tr></table>""");
        assertEquals(List.of(ProxyInfo.parse("1.1.1.1:80")), out);
    }

    @Test
    void jsonFormats() {
        assertEquals(1, parse(SourceKind.GEONODE_JSON, "{\"data\":[{\"ip\":\"1.1.1.1\",\"port\":\"80\"}]}").size());
        assertEquals(1, parse(SourceKind.JSONL, """
                {"host":"1.1.1.1","port":80,"type":"http"}
                {"host":"2.2.2.2","port":80,"type":"socks5"}
                not json""").size());
        List<ProxyInfo> databay = parse(SourceKind.DATABAY_JSON, "{\"data\":[{\"ip\":\"1.1.1.1\",\"port\":80,\"latency_ms\":120}]}");
        assertEquals(120, databay.get(0).latencyMs());
        List<ProxyInfo> ps = parse(SourceKind.PROXYSCRAPE_JSON, """
                [{"ip":"1.1.1.1","port":80,"uptime_percent":90,"latency_ms":100},
                 {"ip":"2.2.2.2","port":80,"uptime_percent":10},
                 {"ip":"3.3.3.3","port":80,"latency_ms":5000}]""");
        assertEquals(List.of(ProxyInfo.parse("1.1.1.1:80")), ps);
        assertEquals(90.0, ps.get(0).uptimePercent());
    }

    @Test
    void proxyInfoParse() {
        ProxyInfo p = ProxyInfo.parse("socks5h://user:p%40ss@host.example:1080");
        assertEquals("host.example", p.host());
        assertEquals(1080, p.port());
        assertEquals(ProxyProtocol.SOCKS5, p.protocol());
        assertEquals("user", p.username());
    }

    @Test
    void defaultsCatalogue() {
        assertEquals(74, ProxySources.defaults().size());
        ProxySources.defaults(ProxyProtocol.SOCKS4).forEach(s -> assertEquals(ProxyProtocol.SOCKS4, s.protocol()));
    }
}
