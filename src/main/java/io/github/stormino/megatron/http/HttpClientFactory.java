package io.github.stormino.megatron.http;

import io.github.stormino.megatron.proxy.ProxyInfo;
import io.github.stormino.megatron.proxy.ProxyProtocol;
import okhttp3.ConnectionPool;
import okhttp3.Credentials;
import okhttp3.Dispatcher;
import okhttp3.Dns;
import okhttp3.OkHttpClient;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Builds OkHttp clients routed through a given proxy. All clients share one connection pool and
 * dispatcher, so creating one per request is cheap.
 */
public final class HttpClientFactory {

    public static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Safari/537.36";

    private static final Dns IPV4_ONLY = hostname -> {
        List<InetAddress> v4 = Dns.SYSTEM.lookup(hostname).stream()
                .filter(a -> a instanceof Inet4Address).toList();
        if (v4.isEmpty()) {
            throw new UnknownHostException(hostname + " has no IPv4 address (required by SOCKS4)");
        }
        return v4;
    };

    private final OkHttpClient base;
    private final String userAgent;

    public HttpClientFactory() {
        this(DEFAULT_USER_AGENT);
    }

    public HttpClientFactory(String userAgent) {
        this(new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(32, 30, TimeUnit.SECONDS))
                .dispatcher(new Dispatcher())
                .retryOnConnectionFailure(false)
                .build(), userAgent);
    }

    public HttpClientFactory(OkHttpClient base, String userAgent) {
        this.base = base;
        this.userAgent = userAgent == null ? DEFAULT_USER_AGENT : userAgent;
    }

    public String userAgent() {
        return userAgent;
    }

    /** Client without proxy (direct connection). */
    public OkHttpClient direct(Duration connectTimeout, Duration readTimeout) {
        return base.newBuilder()
                .proxy(Proxy.NO_PROXY)
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                .build();
    }

    /**
     * Client routed through {@code proxy}; {@code null} means a direct connection.
     *
     * @param callTimeout overall cap for a call, or {@link Duration#ZERO} for none
     */
    public OkHttpClient forProxy(ProxyInfo proxy, Duration connectTimeout, Duration readTimeout,
                                 Duration callTimeout, boolean followRedirects) {
        OkHttpClient.Builder b = base.newBuilder()
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                .callTimeout(callTimeout)
                .followRedirects(followRedirects)
                .followSslRedirects(followRedirects);
        if (proxy == null) {
            return b.proxy(Proxy.NO_PROXY).build();
        }
        // Unresolved address: the proxy resolves the target host (socks5h semantics).
        InetSocketAddress address = InetSocketAddress.createUnresolved(proxy.host(), proxy.port());
        if (proxy.protocol() == ProxyProtocol.SOCKS4) {
            // The JDK cannot speak SOCKS4 to unresolved targets: tunnel through our own client.
            // SOCKS4 carries IPv4 only, so the target is resolved locally to IPv4 addresses.
            return b.proxy(Proxy.NO_PROXY)
                    .socketFactory(new Socks4SocketFactory(address, proxy.username()))
                    .dns(IPV4_ONLY)
                    .build();
        }
        Proxy.Type type = proxy.protocol() == ProxyProtocol.HTTP ? Proxy.Type.HTTP : Proxy.Type.SOCKS;
        b.proxy(new Proxy(type, address));
        if (type == Proxy.Type.HTTP && proxy.username() != null && !proxy.username().isEmpty()) {
            String credential = Credentials.basic(proxy.username(), proxy.password() == null ? "" : proxy.password());
            b.proxyAuthenticator((route, response) -> {
                if (response.request().header("Proxy-Authorization") != null) {
                    return null; // already tried, give up
                }
                return response.request().newBuilder().header("Proxy-Authorization", credential).build();
            });
        }
        return b.build();
    }

    public OkHttpClient forProxy(ProxyInfo proxy, Duration connectTimeout, Duration readTimeout) {
        return forProxy(proxy, connectTimeout, readTimeout, Duration.ZERO, true);
    }
}
