package io.github.stormino.megatron.http;

import javax.net.SocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

/**
 * Socket factory that tunnels every connection through a SOCKS4 (or SOCKS4a) proxy.
 *
 * <p>The JDK's SOCKS client tries SOCKS5 first and falls back to SOCKS4 only for already-resolved
 * addresses, which in practice makes SOCKS4-only proxies unusable. This implements the protocol
 * directly instead:
 * <pre>
 *   request: VER=4 | CMD=1 (connect) | DSTPORT (2) | DSTIP (4) | USERID | 0x00 [| HOST | 0x00 for 4a]
 *   reply:   0x00 | STATUS (0x5A = granted) | ignored (6)
 * </pre>
 * Use it with {@code Proxy.NO_PROXY}: OkHttp resolves the target and calls
 * {@link Socket#connect(SocketAddress, int)} with it, the socket connects to the proxy instead and
 * performs the handshake, and TLS is layered on top as usual. Resolved IPv4 targets use plain
 * SOCKS4; unresolved targets use SOCKS4a (the proxy resolves the name). SOCKS4 cannot carry IPv6.
 */
public final class Socks4SocketFactory extends SocketFactory {

    static final int VERSION = 4;
    static final int CONNECT = 1;
    static final int GRANTED = 0x5A;

    private final InetSocketAddress proxy;
    private final String userId;

    /**
     * @param proxy  proxy address; may be unresolved (resolved locally when connecting)
     * @param userId SOCKS4 user id, usually empty
     */
    public Socks4SocketFactory(InetSocketAddress proxy, String userId) {
        this.proxy = proxy;
        this.userId = userId == null ? "" : userId;
    }

    @Override
    public Socket createSocket() {
        return new Socks4Socket(proxy, userId);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return connected(InetSocketAddress.createUnresolved(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return connected(InetSocketAddress.createUnresolved(host, port));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return connected(new InetSocketAddress(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        return connected(new InetSocketAddress(address, port));
    }

    private Socket connected(InetSocketAddress target) throws IOException {
        Socket s = createSocket();
        s.connect(target);
        return s;
    }

    /** A plain TCP socket whose {@code connect} goes through the SOCKS4 handshake. */
    static final class Socks4Socket extends Socket {

        private final InetSocketAddress proxy;
        private final String userId;

        Socks4Socket(InetSocketAddress proxy, String userId) {
            this.proxy = proxy;
            this.userId = userId;
        }

        @Override
        public void connect(SocketAddress endpoint) throws IOException {
            connect(endpoint, 0);
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            if (!(endpoint instanceof InetSocketAddress target)) {
                throw new IllegalArgumentException("Unsupported address type: " + endpoint);
            }
            byte[] request = buildRequest(target, userId);
            InetSocketAddress proxyAddress = proxy.isUnresolved()
                    ? new InetSocketAddress(proxy.getHostString(), proxy.getPort())
                    : proxy;
            super.connect(proxyAddress, timeout);
            int previousTimeout = getSoTimeout();
            try {
                // Bound the handshake by the connect timeout: a dead proxy that accepts TCP but never
                // answers must not hang the caller.
                setSoTimeout(timeout);
                OutputStream out = getOutputStream();
                out.write(request);
                out.flush();
                checkReply(readReply(getInputStream()), target);
                setSoTimeout(previousTimeout);
            } catch (IOException | RuntimeException e) {
                try {
                    close();
                } catch (IOException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            }
        }
    }

    static byte[] buildRequest(InetSocketAddress target, String userId) throws SocketException {
        byte[] user = userId.getBytes(StandardCharsets.ISO_8859_1);
        byte[] ip;
        byte[] host = null;
        if (target.isUnresolved()) {
            // SOCKS4a: invalid IP 0.0.0.x (x != 0) tells the proxy to resolve the trailing host name.
            ip = new byte[]{0, 0, 0, 1};
            host = target.getHostString().getBytes(StandardCharsets.ISO_8859_1);
        } else if (target.getAddress() instanceof Inet4Address v4) {
            ip = v4.getAddress();
        } else {
            throw new SocketException("SOCKS4 cannot connect to IPv6 address " + target.getAddress());
        }
        int len = 8 + user.length + 1 + (host == null ? 0 : host.length + 1);
        byte[] req = new byte[len];
        req[0] = VERSION;
        req[1] = CONNECT;
        req[2] = (byte) (target.getPort() >> 8);
        req[3] = (byte) target.getPort();
        System.arraycopy(ip, 0, req, 4, 4);
        System.arraycopy(user, 0, req, 8, user.length);
        // req[8 + user.length] is already 0 (terminator)
        if (host != null) {
            System.arraycopy(host, 0, req, 9 + user.length, host.length);
            // final terminator already 0
        }
        return req;
    }

    static byte[] readReply(InputStream in) throws IOException {
        byte[] reply = new byte[8];
        int off = 0;
        while (off < reply.length) {
            int n = in.read(reply, off, reply.length - off);
            if (n < 0) {
                throw new SocketException("SOCKS4 proxy closed the connection during the handshake");
            }
            off += n;
        }
        return reply;
    }

    static void checkReply(byte[] reply, InetSocketAddress target) throws SocketException {
        if (reply[0] != 0) {
            throw new SocketException("Not a SOCKS4 proxy (reply version " + (reply[0] & 0xFF) + ")");
        }
        int status = reply[1] & 0xFF;
        if (status != GRANTED) {
            String reason = switch (status) {
                case 0x5B -> "request rejected or failed";
                case 0x5C -> "proxy cannot reach identd";
                case 0x5D -> "identd user id mismatch";
                default -> "status 0x" + Integer.toHexString(status);
            };
            throw new SocketException("SOCKS4 connect to " + target + " refused: " + reason);
        }
    }
}
