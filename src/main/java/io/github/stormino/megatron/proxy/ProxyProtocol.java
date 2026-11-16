package io.github.stormino.megatron.proxy;

/**
 * Proxy protocol.
 * <ul>
 *   <li>{@link #HTTP}: plain HTTP proxy, HTTPS through {@code CONNECT}.</li>
 *   <li>{@link #SOCKS5}: the JDK's SOCKS client; host names are resolved by the proxy.</li>
 *   <li>{@link #SOCKS4}: the library's own client ({@code Socks4SocketFactory}); targets are
 *       resolved locally to IPv4, since SOCKS4 carries neither host names nor IPv6.</li>
 * </ul>
 */
public enum ProxyProtocol {
    HTTP,
    SOCKS4,
    SOCKS5;

    public static ProxyProtocol parse(String value) {
        if (value == null) {
            return HTTP;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "socks5", "socks5h" -> SOCKS5;
            case "socks4", "socks4a" -> SOCKS4;
            default -> HTTP;
        };
    }

    public String scheme() {
        return switch (this) {
            case HTTP -> "http";
            case SOCKS4 -> "socks4";
            case SOCKS5 -> "socks5h";
        };
    }
}
