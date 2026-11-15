package io.github.stormino.megatron.proxy;

/**
 * A public proxy list.
 *
 * @param protocol protocol of every proxy in the list (overrides whatever the parser infers)
 */
public record ProxySource(String name, String url, SourceKind kind, ProxyProtocol protocol) {

    public ProxySource(String name, String url, SourceKind kind) {
        this(name, url, kind, ProxyProtocol.HTTP);
    }
}
