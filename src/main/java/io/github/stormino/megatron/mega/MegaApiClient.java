package io.github.stormino.megatron.mega;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.stormino.megatron.http.HttpClientFactory;
import io.github.stormino.megatron.proxy.ProxyInfo;
import io.github.stormino.megatron.util.FileNames;
import io.github.stormino.megatron.util.Threads;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Minimal client for Mega's public API: resolves public file links and shared-folder nodes to
 * CDN URLs and lists shared folders. No login, no upload.
 *
 * <p>Instances are cheap and immutable; pass the proxy per call.
 */
public final class MegaApiClient {

    private static final Logger log = LoggerFactory.getLogger(MegaApiClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json");
    private static final AtomicLong SEQUENCE = new AtomicLong(ThreadLocalRandom.current().nextLong(0, 0xFFFFFFFFL));

    /** Mega error code: temporary congestion, retry. */
    public static final int EAGAIN = -3;

    public static final String DEFAULT_API_URL = "https://g.api.mega.co.nz/cs";

    private final HttpClientFactory http;
    private final String apiUrl;
    private final Duration timeout;
    private final int maxAttempts;
    private final BooleanSupplier shouldAbort;

    public MegaApiClient(HttpClientFactory http) {
        this(http, DEFAULT_API_URL, Duration.ofSeconds(30), 5, () -> false);
    }

    /**
     * @param shouldAbort polled during back-off sleeps so a cancellation is not delayed by retries
     */
    public MegaApiClient(HttpClientFactory http, String apiUrl, Duration timeout, int maxAttempts,
                         BooleanSupplier shouldAbort) {
        this.http = http;
        this.apiUrl = apiUrl;
        this.timeout = timeout;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.shouldAbort = shouldAbort == null ? () -> false : shouldAbort;
    }

    public MegaApiClient withAbort(BooleanSupplier shouldAbort) {
        return new MegaApiClient(http, apiUrl, timeout, maxAttempts, shouldAbort);
    }

    /** Same client with a different number of attempts per call (network errors and EAGAIN). */
    public MegaApiClient withAttempts(int attempts) {
        return new MegaApiClient(http, apiUrl, timeout, attempts, shouldAbort);
    }

    /** Same client with a different per-call timeout. */
    public MegaApiClient withTimeout(Duration timeout) {
        return new MegaApiClient(http, apiUrl, timeout, maxAttempts, shouldAbort);
    }

    /**
     * Resolves a file link (public file link or internal folder-job link) to its CDN URL.
     *
     * @param proxy proxy for the API call, or {@code null} for a direct connection
     */
    public ResolvedFile resolve(String megaUrl, ProxyInfo proxy) {
        var job = MegaLinks.parseFolderJob(megaUrl);
        if (job.isPresent()) {
            return resolveFolderNode(job.get(), proxy);
        }
        MegaLinks.FileLink link = MegaLinks.parseFile(megaUrl).orElseThrow(() ->
                MegaLinks.isFolderLink(megaUrl)
                        ? new MegaApiException("Folder links must be expanded first: " + megaUrl)
                        : new MegaApiException("Not a Mega file link: " + megaUrl));
        MegaCrypto.FileKey key;
        try {
            key = MegaCrypto.deriveFileKey(MegaCrypto.base64ToA32(link.keyB64()));
        } catch (IllegalArgumentException e) {
            throw new MegaApiException("Invalid file key in link: " + e.getMessage());
        }
        ObjectNode payload = MAPPER.createObjectNode().put("a", "g").put("g", 1).put("p", link.handle());
        JsonNode resp = request(payload, Map.of(), proxy);
        String cdnUrl = resp.path("g").asText(null);
        if (cdnUrl == null || cdnUrl.isEmpty()) {
            throw new MegaApiException("File not accessible: " + resp);
        }
        long size = readSize(resp);
        String name = null;
        if (resp.hasNonNull("at")) {
            JsonNode attrs = MegaCrypto.decryptAttr(MegaCrypto.base64UrlDecode(resp.get("at").asText()), key.aesKey());
            if (attrs != null && attrs.hasNonNull("n")) {
                name = attrs.get("n").asText();
            }
        }
        String fallback = "mega_" + link.handle();
        String fileName = name == null ? fallback : FileNames.sanitizeFileName(name, fallback);
        return new ResolvedFile(link.handle(), key, cdnUrl, size, fileName);
    }

    /**
     * Resolves one node of a shared folder. Note the two different {@code n}: in the payload it
     * is the node handle, in the query string the folder id that gives the call its context.
     */
    public ResolvedFile resolveFolderNode(MegaLinks.FolderJobLink job, ProxyInfo proxy) {
        int[] raw = MegaCrypto.base64ToA32(job.nodeKeyB64());
        if (raw.length < 8) {
            throw new MegaApiException("Node key too short (" + raw.length + " words) for " + job.nodeHandle());
        }
        MegaCrypto.FileKey key = MegaCrypto.deriveFileKey(raw);
        ObjectNode payload = MAPPER.createObjectNode().put("a", "g").put("g", 1).put("n", job.nodeHandle());
        JsonNode resp = request(payload, Map.of("n", job.folderId()), proxy);
        String cdnUrl = resp.path("g").asText(null);
        if (cdnUrl == null || cdnUrl.isEmpty()) {
            throw new MegaApiException("Folder file not accessible: " + resp);
        }
        String fallback = "mega_" + job.nodeHandle();
        return new ResolvedFile(job.nodeHandle(), key, cdnUrl, readSize(resp),
                FileNames.sanitizeFileName(job.fileName(), fallback));
    }

    /** Lists every node of a shared folder, recursively, in one call. Decryption is up to the caller. */
    public List<JsonNode> listFolder(String folderId, ProxyInfo proxy) {
        ObjectNode payload = MAPPER.createObjectNode().put("a", "f").put("c", 1).put("r", 1).put("ca", 1);
        JsonNode resp = request(payload, Map.of("n", folderId), proxy);
        JsonNode nodes = resp.get("f");
        if (nodes == null || !nodes.isArray()) {
            throw new MegaApiException("Folder listing unavailable: " + resp);
        }
        List<JsonNode> out = new ArrayList<>();
        nodes.forEach(n -> {
            if (n.isObject()) {
                out.add(n);
            }
        });
        return out;
    }

    private static long readSize(JsonNode resp) {
        JsonNode s = resp.get("s");
        if (s == null || !s.canConvertToLong()) {
            throw new MegaApiException("Size missing from API response: " + resp);
        }
        return s.asLong();
    }

    /**
     * POSTs {@code [payload]} and returns the first element. Retries network errors and
     * {@code -3} (EAGAIN) with capped, interruptible exponential back-off.
     */
    JsonNode request(ObjectNode payload, Map<String, String> extraParams, ProxyInfo proxy) {
        ArrayNode body = MAPPER.createArrayNode().add(payload);
        OkHttpClient client = http.forProxy(proxy, timeout, timeout, timeout.multipliedBy(2), true);
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            HttpUrl.Builder url = HttpUrl.get(apiUrl).newBuilder()
                    .addQueryParameter("id", Long.toString(SEQUENCE.getAndIncrement()));
            extraParams.forEach(url::addQueryParameter);
            Request req = new Request.Builder()
                    .url(url.build())
                    .header("User-Agent", http.userAgent())
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
            JsonNode data;
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    throw new IOException("HTTP " + resp.code());
                }
                data = MAPPER.readTree(resp.body() == null ? "" : resp.body().string());
            } catch (IOException e) {
                last = e;
                log.debug("Mega API attempt {}/{} failed via {}: {}", attempt, maxAttempts, proxy, e.toString());
                backoff(attempt);
                continue;
            }
            JsonNode item = data != null && data.isArray() && !data.isEmpty() ? data.get(0) : data;
            if (item != null && item.isIntegralNumber()) {
                int code = item.asInt();
                if (code == 0) {
                    return MAPPER.createObjectNode();
                }
                if (code == EAGAIN) {
                    last = new MegaApiException("EAGAIN", code);
                    backoff(attempt);
                    continue;
                }
                throw new MegaApiException("Mega API error " + code + " (" + describe(code) + ")", code);
            }
            if (item == null || !item.isObject()) {
                throw new MegaApiException("Unexpected API response: " + data);
            }
            return item;
        }
        throw new MegaApiException("Mega API retries exhausted: " + (last == null ? "?" : last.getMessage()), 0, last);
    }

    private void backoff(int attempt) {
        if (attempt >= maxAttempts) {
            return;
        }
        if (Threads.sleepInterruptibly(Math.min(30, 1 << attempt), shouldAbort)) {
            throw new MegaApiException("Resolve cancelled");
        }
    }

    /** Human-readable meaning of the most common Mega error codes. */
    public static String describe(int code) {
        return switch (code) {
            case -2 -> "bad arguments";
            case -3 -> "temporary congestion";
            case -4 -> "rate limited";
            case -6 -> "too many requests";
            case -9 -> "not found";
            case -11 -> "access denied";
            case -14 -> "decryption failed";
            case -16 -> "blocked / taken down";
            case -17 -> "over quota";
            case -18 -> "temporarily unavailable";
            default -> "unknown";
        };
    }
}
