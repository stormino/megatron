package io.github.stormino.megatron.proxy;

import java.util.List;

/** Built-in catalogue of free public proxy lists. */
public final class ProxySources {

    private ProxySources() {
    }

    /** Every built-in source (HTTP, SOCKS5 and SOCKS4). */
    public static List<ProxySource> defaults() {
        return DEFAULTS;
    }

    /** Built-in sources restricted to the given protocols. */
    public static List<ProxySource> defaults(ProxyProtocol... protocols) {
        List<ProxyProtocol> wanted = List.of(protocols);
        return DEFAULTS.stream().filter(s -> wanted.contains(s.protocol())).toList();
    }

    private static final List<ProxySource> DEFAULTS = List.of(
            new ProxySource("free-proxy-list", "https://free-proxy-list.net/", SourceKind.HTML_TABLE, ProxyProtocol.HTTP),
            new ProxySource("sslproxies", "https://www.sslproxies.org/", SourceKind.HTML_TABLE, ProxyProtocol.HTTP),
            new ProxySource("us-proxy", "https://www.us-proxy.org/", SourceKind.HTML_TABLE, ProxyProtocol.HTTP),
            new ProxySource("free-proxy-list-anonymous", "https://free-proxy-list.net/anonymous-proxy.html", SourceKind.HTML_TABLE, ProxyProtocol.HTTP),
            new ProxySource("proxyscrape-http", "https://api.proxyscrape.com/v2/?request=getproxies&protocol=http&timeout=10000&country=all", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("proxy-list-download-http", "https://www.proxy-list.download/api/v1/get?type=http", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("openproxylist-http", "https://api.openproxylist.xyz/http.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("proxyspace-http", "https://proxyspace.pro/http.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("thespeedx-http", "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("monosans-http", "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("clarketm-proxy-list", "https://raw.githubusercontent.com/clarketm/proxy-list/master/proxy-list-raw.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("jetkai-http", "https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-http.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("mmpx12-http", "https://raw.githubusercontent.com/mmpx12/proxy-list/master/http.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("proxifly-http", "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/http/data.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("hookzof-socks5", "https://raw.githubusercontent.com/hookzof/socks5_list/master/proxy.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.SOCKS5),
            new ProxySource("geonode-http", "https://proxylist.geonode.com/api/proxy-list?limit=500&page=1&sort_by=lastChecked&sort_type=desc&protocols=http%2Chttps", SourceKind.GEONODE_JSON, ProxyProtocol.HTTP),
            new ProxySource("gfpcom-http", "https://raw.githubusercontent.com/gfpcom/free-proxy-list/main/proxies/http.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("vakhov-http", "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/http.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("proxygenerator1-http", "https://raw.githubusercontent.com/proxygenerator1/ProxyGenerator/main/http_proxies.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("shiftytr-http", "https://raw.githubusercontent.com/ShiftyTR/Proxy-List/master/http.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("roosterkid-https", "https://raw.githubusercontent.com/roosterkid/openproxylist/main/HTTPS_RAW.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("murongpig-http", "https://raw.githubusercontent.com/MuRongPIG/Proxy-Master/main/http.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("zloi-user-http", "https://raw.githubusercontent.com/zloi-user/hideip.me/master/http.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("b4rc0de-http", "https://raw.githubusercontent.com/B4RC0DE-TM/proxy-list/main/HTTP.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("proxy4parsing-http", "https://raw.githubusercontent.com/proxy4parsing/proxy-list/main/http.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("almroot-http", "https://raw.githubusercontent.com/almroot/proxylist/master/list.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("proxyscrape-v4-http", "https://api.proxyscrape.com/v4/free-proxy-list/get?request=display_proxies&proxy_format=protocolipport&format=text&protocol=http", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("spys-me-http", "https://spys.me/proxy.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("openproxy-space-http", "https://openproxy.space/list/http", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("databay-strict-ssl", "https://databay.com/api/v1/proxy-list?protocol=http&ssl=strict&format=json&limit=1000", SourceKind.DATABAY_JSON, ProxyProtocol.HTTP),
            new ProxySource("proxyscrape-gh-http", "https://raw.githubusercontent.com/proxyscrape/free-proxy-list/main/proxies/protocols/http/data.json", SourceKind.PROXYSCRAPE_JSON, ProxyProtocol.HTTP),
            new ProxySource("proxyscrape-gh-socks5", "https://raw.githubusercontent.com/proxyscrape/free-proxy-list/main/proxies/protocols/socks5/data.json", SourceKind.PROXYSCRAPE_JSON, ProxyProtocol.SOCKS5),
            new ProxySource("proxyscrape-gh-socks4", "https://raw.githubusercontent.com/proxyscrape/free-proxy-list/main/proxies/protocols/socks4/data.json", SourceKind.PROXYSCRAPE_JSON, ProxyProtocol.SOCKS4),
            new ProxySource("pubproxy-fresh", "http://pubproxy.com/api/proxy?limit=20&format=txt&type=http&https=true&last_check=15", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("fate0-proxylist", "https://raw.githubusercontent.com/fate0/proxylist/master/proxy.list", SourceKind.JSONL, ProxyProtocol.HTTP),
            new ProxySource("jetkai-https", "https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-https.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("shiftytr-https", "https://raw.githubusercontent.com/ShiftyTR/Proxy-List/master/https.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("mmpx12-https", "https://raw.githubusercontent.com/mmpx12/proxy-list/master/https.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("vakhov-https", "https://vakhov.github.io/fresh-proxy-list/https.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("vpslab-http-all", "https://raw.githubusercontent.com/VPSLabCloud/VPSLab-Free-Proxy-List/main/http_all.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("vpslab-http-ssl", "https://raw.githubusercontent.com/VPSLabCloud/VPSLab-Free-Proxy-List/main/http_ssl.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("vpslab-http-elite", "https://raw.githubusercontent.com/VPSLabCloud/VPSLab-Free-Proxy-List/main/http_elite.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("vmheaven-http", "https://raw.githubusercontent.com/vmheaven/VMHeaven-Free-Proxy-Updated/main/http.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("vmheaven-https", "https://raw.githubusercontent.com/vmheaven/VMHeaven-Free-Proxy-Updated/main/https.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("komutan234-http", "https://raw.githubusercontent.com/komutan234/Proxy-List-Free/main/proxies/http.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("rdavydov-http", "https://raw.githubusercontent.com/rdavydov/proxy-list/main/proxies/http.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("zevtyardt-http", "https://raw.githubusercontent.com/zevtyardt/proxy-list/main/http.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("kangproxy-http", "https://raw.githubusercontent.com/officialputuid/KangProxy/master/http.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("thordata-http", "https://raw.githubusercontent.com/Thordata/awesome-free-proxy-list/main/proxies/http.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("thordata-https", "https://raw.githubusercontent.com/Thordata/awesome-free-proxy-list/main/proxies/https.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("zaeem20-http", "https://raw.githubusercontent.com/Zaeem20/FREE_PROXIES_LIST/master/http.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("zaeem20-https", "https://raw.githubusercontent.com/Zaeem20/FREE_PROXIES_LIST/master/https.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("ercindedeoglu-http", "https://raw.githubusercontent.com/ErcinDedeoglu/proxies/main/http.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("yemixzy-http", "https://raw.githubusercontent.com/yemixzy/proxy-list/main/proxies/http.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("proxygenerator1-stable-http", "https://raw.githubusercontent.com/proxygenerator1/ProxyGenerator/main/Stable/http.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.HTTP),
            new ProxySource("thespeedx-socks5", "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.SOCKS5),
            new ProxySource("monosans-socks5", "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.SOCKS5),
            new ProxySource("shiftytr-socks5", "https://raw.githubusercontent.com/ShiftyTR/Proxy-List/master/socks5.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.SOCKS5),
            new ProxySource("jetkai-socks5", "https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-socks5.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.SOCKS5),
            new ProxySource("roosterkid-socks5", "https://raw.githubusercontent.com/roosterkid/openproxylist/main/SOCKS5_RAW.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.SOCKS5),
            new ProxySource("mmpx12-socks5", "https://raw.githubusercontent.com/mmpx12/proxy-list/master/socks5.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.SOCKS5),
            new ProxySource("vakhov-socks5", "https://vakhov.github.io/fresh-proxy-list/socks5.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.SOCKS5),
            new ProxySource("zloi-user-socks5", "https://raw.githubusercontent.com/zloi-user/hideip.me/main/socks5.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.SOCKS5),
            new ProxySource("rdavydov-socks5", "https://raw.githubusercontent.com/rdavydov/proxy-list/main/proxies/socks5.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.SOCKS5),
            new ProxySource("zaeem20-socks5", "https://raw.githubusercontent.com/Zaeem20/FREE_PROXIES_LIST/master/socks5.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.SOCKS5),
            new ProxySource("ercindedeoglu-socks5", "https://raw.githubusercontent.com/ErcinDedeoglu/proxies/main/socks5.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.SOCKS5),
            new ProxySource("thordata-socks5", "https://raw.githubusercontent.com/Thordata/awesome-free-proxy-list/main/proxies/socks5.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.SOCKS5),
            new ProxySource("yemixzy-socks5", "https://raw.githubusercontent.com/yemixzy/proxy-list/main/proxies/socks5.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.SOCKS5),
            new ProxySource("proxifly-socks5", "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/socks5/data.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.SOCKS5),
            new ProxySource("thespeedx-socks4", "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks4.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.SOCKS4),
            new ProxySource("monosans-socks4", "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks4.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.SOCKS4),
            new ProxySource("shiftytr-socks4", "https://raw.githubusercontent.com/ShiftyTR/Proxy-List/master/socks4.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.SOCKS4),
            new ProxySource("roosterkid-socks4", "https://raw.githubusercontent.com/roosterkid/openproxylist/main/SOCKS4_RAW.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.SOCKS4),
            new ProxySource("jetkai-socks4", "https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-socks4.txt", SourceKind.PLAIN_TEXT, ProxyProtocol.SOCKS4)
    );
}
