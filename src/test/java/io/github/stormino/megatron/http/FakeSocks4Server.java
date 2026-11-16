package io.github.stormino.megatron.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Minimal SOCKS4/4a server for tests: records each request, optionally refuses, then relays bytes. */
final class FakeSocks4Server implements AutoCloseable {

    record Request(int version, int command, int port, String ip, String userId, String host) {
    }

    final List<Request> requests = new CopyOnWriteArrayList<>();
    volatile int replyStatus = 0x5A;
    private final ServerSocket server;

    FakeSocks4Server() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(this::acceptLoop, "fake-socks4");
        t.setDaemon(true);
        t.start();
    }

    int port() {
        return server.getLocalPort();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Thread t = new Thread(() -> handle(client), "fake-socks4-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket client) {
        try (client) {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            int version = in.read();
            int command = in.read();
            int port = (in.read() << 8) | in.read();
            byte[] ip = in.readNBytes(4);
            String user = readZeroTerminated(in);
            String ipText = (ip[0] & 0xFF) + "." + (ip[1] & 0xFF) + "." + (ip[2] & 0xFF) + "." + (ip[3] & 0xFF);
            String host = ip[0] == 0 && ip[1] == 0 && ip[2] == 0 && ip[3] != 0 ? readZeroTerminated(in) : null;
            requests.add(new Request(version, command, port, ipText, user, host));
            int status = replyStatus;
            out.write(new byte[]{0, (byte) status, 0, 0, 0, 0, 0, 0});
            out.flush();
            if (status != 0x5A) {
                return;
            }
            try (Socket upstream = new Socket()) {
                upstream.connect(new InetSocketAddress(host != null ? host : ipText, port), 5000);
                Thread up = new Thread(() -> pipe(in, upstream), "fake-socks4-up");
                up.setDaemon(true);
                up.start();
                upstream.getInputStream().transferTo(out);
            }
        } catch (IOException ignored) {
            // connection closed
        }
    }

    private static void pipe(InputStream in, Socket upstream) {
        try {
            in.transferTo(upstream.getOutputStream());
            upstream.shutdownOutput();
        } catch (IOException ignored) {
            // closed
        }
    }

    private static String readZeroTerminated(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) > 0) {
            buf.write(b);
        }
        return buf.toString(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
