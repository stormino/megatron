package io.github.stormino.megatron.proxy;

/**
 * Proxy protocol. SOCKS proxies are reached through the JDK's SOCKS client, which negotiates
 * SOCKS5 and resolves host names on the proxy side; SOCKS4-only servers usually fail validation
 * because SOCKS4 cannot carry unresolved host names.
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
