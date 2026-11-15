package io.github.stormino.megatron.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.stormino.megatron.mega.MegaCrypto;
import io.github.stormino.megatron.mega.MegaMac;
import io.github.stormino.megatron.proxy.ProxyInfo;
import io.github.stormino.megatron.proxy.ProxyProtocol;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Fake Mega API + CDN reachable only through "proxies": several MockWebServers sharing one
 * dispatcher, each acting as a distinct HTTP proxy.
 */
final class FakeMega extends Dispatcher implements AutoCloseable {

    static final String API = "http://api.test/cs";
    private static final ObjectMapper M = new ObjectMapper();

    record StoredFile(String handle, int[] rawKey, String name, byte[] plain, byte[] cipher) {
        String link() {
            return "https://mega.nz/file/" + handle + "#" + MegaCrypto.a32ToBase64(rawKey);
        }
    }

    final List<MockWebServer> servers = new ArrayList<>();
    final Map<String, StoredFile> files = new ConcurrentHashMap<>();
    final Map<String, JsonNode> folders = new ConcurrentHashMap<>();
    /** Scripted CDN failures, consumed in order before normal responses. */
    final Queue<MockResponse> cdnScript = new ConcurrentLinkedQueue<>();
    final List<String> cdnRanges = new CopyOnWriteArrayList<>();
    volatile Function<String, MockResponse> cdnOverride = range -> null;
    volatile int cdnGeneration = 1;

    FakeMega(int proxies) throws IOException {
        for (int i = 0; i < proxies; i++) {
            MockWebServer s = new MockWebServer();
            s.setDispatcher(this);
            s.start();
            servers.add(s);
        }
    }

    List<ProxyInfo> proxies() {
        return servers.stream().map(s -> new ProxyInfo(s.getHostName(), s.getPort(), ProxyProtocol.HTTP)).toList();
    }

    StoredFile addFile(String handle, String name, int size, long seed) {
        Random r = new Random(seed);
        byte[] plain = new byte[size];
        r.nextBytes(plain);
        int[] raw = keyFor(plain, seed);
        StoredFile f = new StoredFile(handle, raw, name, plain, encrypt(raw, plain));
        files.put(handle, f);
        return f;
    }

    /** A valid Mega file key for {@code plain}: random AES key and nonce, real meta-MAC. */
    static int[] keyFor(byte[] plain, long seed) {
        Random r = new Random(seed * 31 + 7);
        int[] aes = {r.nextInt(), r.nextInt(), r.nextInt(), r.nextInt()};
        long nonce = r.nextLong();
        try {
            long mac = MegaMac.metaMac(new java.io.ByteArrayInputStream(plain), plain.length,
                    new MegaCrypto.FileKey(aes, nonce, 0));
            return MegaMac.packFileKey(aes, nonce, mac);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] encrypt(int[] raw, byte[] plain) {
        try {
            return MegaCrypto.ctrCipherAt(MegaCrypto.deriveFileKey(raw), 0).doFinal(plain);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public MockResponse dispatch(RecordedRequest request) {
        String line = request.getRequestLine();
        try {
            if (line.contains("api.test/cs")) {
                return api(request);
            }
            if (line.contains("cdn.test/")) {
                return cdn(request);
            }
        } catch (Exception e) {
            return new MockResponse().setResponseCode(500).setBody(e.toString());
        }
        return new MockResponse().setResponseCode(404);
    }

    private MockResponse api(RecordedRequest request) throws IOException {
        JsonNode payload = M.readTree(request.getBody().readUtf8()).get(0);
        String a = payload.path("a").asText();
        ArrayNode out = M.createArrayNode();
        if (a.equals("f")) {
            String folderId = okhttp3.HttpUrl.get(request.getRequestLine().split(" ")[1]).queryParameter("n");
            JsonNode listing = folders.get(folderId);
            if (listing == null) {
                return new MockResponse().setBody("[-9]");
            }
            out.add(listing);
            return new MockResponse().setBody(out.toString());
        }
        String handle = payload.hasNonNull("p") ? payload.get("p").asText() : payload.path("n").asText();
        StoredFile f = files.get(handle);
        if (f == null) {
            return new MockResponse().setBody("[-9]");
        }
        var item = out.addObject();
        item.put("s", f.plain().length);
        item.put("g", "http://cdn.test/" + cdnGeneration + "/" + handle);
        item.put("at", MegaCrypto.base64UrlEncode(MegaCrypto.encryptAttr("{\"n\":\"" + f.name() + "\"}",
                MegaCrypto.deriveFileKey(f.rawKey()).aesKey())));
        return new MockResponse().setBody(out.toString());
    }

    private MockResponse cdn(RecordedRequest request) {
        String range = request.getHeader("Range");
        MockResponse scripted = cdnScript.poll();
        if (scripted != null) {
            return scripted;
        }
        MockResponse override = cdnOverride.apply(range);
        if (override != null) {
            return override;
        }
        String path = request.getRequestLine().split(" ")[1];
        String[] parts = path.substring(path.indexOf("cdn.test/") + 9).split("/");
        if (Integer.parseInt(parts[0]) != cdnGeneration) {
            return new MockResponse().setResponseCode(503);
        }
        StoredFile f = files.get(parts[1]);
        cdnRanges.add(range);
        String[] se = range.substring("bytes=".length()).split("-");
        int start = Integer.parseInt(se[0]);
        int end = Integer.parseInt(se[1]);
        Buffer body = new Buffer().write(f.cipher(), start, end - start + 1);
        return new MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes " + start + "-" + end + "/" + f.cipher().length)
                .setBody(body);
    }

    @Override
    public void close() throws IOException {
        for (MockWebServer s : servers) {
            s.shutdown();
        }
    }
}
