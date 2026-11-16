package io.github.stormino.megatron.proxy;

import java.util.Objects;

/**
 * A proxy endpoint plus the metadata gathered about it (latency, measured throughput, origin).
 *
 * <p>Identity ({@link #equals}/{@link #hashCode}) is the {@code host:port} pair only, so the
 * same proxy seen in several lists, or reloaded from the cache, is treated as one.
 * Metadata fields are mutable and thread-safe; they are written by the validator and read by
 * the pool.
 */
public final class ProxyInfo {

    private final String host;
    private final int port;
    private final ProxyProtocol protocol;
    private final String username;
    private final String password;
    private final String source;

    private volatile Integer latencyMs;
    private volatile Double throughputBps;
    private volatile Double uptimePercent;
    private volatile Integer cachedScore;
    private volatile String exitIp;

    public ProxyInfo(String host, int port, ProxyProtocol protocol) {
        this(host, port, protocol, null, null, null);
    }

    public ProxyInfo(String host, int port, ProxyProtocol protocol, String username, String password, String source) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host is required");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("invalid port: " + port);
        }
        this.host = host;
        this.port = port;
        this.protocol = protocol == null ? ProxyProtocol.HTTP : protocol;
        this.username = username;
        this.password = password;
        this.source = source;
    }

    /** Parses {@code host:port} or {@code scheme://[user:pass@]host:port}. */
    public static ProxyInfo parse(String spec) {
        String s = spec.trim();
        ProxyProtocol protocol = ProxyProtocol.HTTP;
        int schemeEnd = s.indexOf("://");
        if (schemeEnd > 0) {
            protocol = ProxyProtocol.parse(s.substring(0, schemeEnd));
            s = s.substring(schemeEnd + 3);
        }
        String user = null;
        String pass = null;
        int at = s.lastIndexOf('@');
        if (at > 0) {
            String creds = s.substring(0, at);
            s = s.substring(at + 1);
            int colon = creds.indexOf(':');
            user = colon >= 0 ? creds.substring(0, colon) : creds;
            pass = colon >= 0 ? creds.substring(colon + 1) : "";
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        int colon = s.lastIndexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("expected host:port, got: " + spec);
        }
        return new ProxyInfo(s.substring(0, colon), Integer.parseInt(s.substring(colon + 1)), protocol, user, pass, null);
    }

    public String key() {
        return host + ":" + port;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public ProxyProtocol protocol() {
        return protocol;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    /** Name of the list the proxy was scraped from, {@code "cache"} or {@code null}. */
    public String source() {
        return source;
    }

    public Integer latencyMs() {
        return latencyMs;
    }

    public void latencyMs(Integer latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Double throughputBps() {
        return throughputBps;
    }

    public void throughputBps(Double throughputBps) {
        this.throughputBps = throughputBps;
    }

    public Double uptimePercent() {
        return uptimePercent;
    }

    public void uptimePercent(Double uptimePercent) {
        this.uptimePercent = uptimePercent;
    }

    /** Score persisted by a previous session (see {@link ProxyCache}), or {@code null}. */
    public Integer cachedScore() {
        return cachedScore;
    }

    public void cachedScore(Integer cachedScore) {
        this.cachedScore = cachedScore;
    }

    /**
     * Public IP address that traffic through this proxy comes from, as measured by the validator,
     * or {@code null} if unknown. Many free proxies are front ends of the same exit; the pool
     * groups them by this value because Mega's quota is per IP.
     */
    public String exitIp() {
        return exitIp;
    }

    public void exitIp(String exitIp) {
        this.exitIp = exitIp;
    }

    /** Grouping key: the exit IP when known, otherwise the proxy itself. */
    public String exitKey() {
        String ip = exitIp;
        return ip != null ? "exit:" + ip : "proxy:" + key();
    }

    /** Proxy URL, e.g. {@code socks5h://1.2.3.4:1080}. Credentials are not included. */
    public String url() {
        return protocol.scheme() + "://" + key();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ProxyInfo other && port == other.port && host.equals(other.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port);
    }

    @Override
    public String toString() {
        return url();
    }
}
