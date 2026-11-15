package io.github.stormino.megatron.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * On-disk JSON cache of good proxies, for a hot start of the next session. Entries older than
 * the TTL are dropped on load; reading or writing never throws.
 */
public final class ProxyCache {

    private static final Logger log = LoggerFactory.getLogger(ProxyCache.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int SCHEMA_VERSION = 1;

    private final Path path;
    private final Duration ttl;

    public ProxyCache(Path path) {
        this(path, Duration.ofHours(6));
    }

    public ProxyCache(Path path, Duration ttl) {
        this.path = path;
        this.ttl = ttl;
    }

    public Path path() {
        return path;
    }

    public List<ProxyInfo> load() {
        List<ProxyInfo> out = new ArrayList<>();
        if (!Files.isRegularFile(path)) {
            return out;
        }
        try {
            JsonNode root = MAPPER.readTree(path.toFile());
            if (root.path("schema").asInt() != SCHEMA_VERSION) {
                log.info("Proxy cache {} has an unknown schema, ignoring it", path);
                return out;
            }
            long minSeen = Instant.now().minus(ttl).getEpochSecond();
            for (JsonNode e : root.path("proxies")) {
                if (e.path("last_seen").asLong(0) < minSeen) {
                    continue;
                }
                try {
                    ProxyInfo p = new ProxyInfo(e.path("host").asText(), e.path("port").asInt(),
                            ProxyProtocol.parse(e.path("protocol").asText("http")), null, null, "cache");
                    if (e.path("score").isInt()) {
                        p.cachedScore(e.get("score").asInt());
                    }
                    if (e.path("latency_ms").isInt()) {
                        p.latencyMs(e.get("latency_ms").asInt());
                    }
                    out.add(p);
                } catch (IllegalArgumentException ignored) {
                    // malformed entry
                }
            }
            log.info("Loaded {} proxies from cache {}", out.size(), path);
        } catch (IOException | RuntimeException e) {
            log.warn("Unreadable proxy cache {}: {}", path, e.toString());
        }
        return out;
    }

    public void save(List<ProxyInfo> proxies) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema", SCHEMA_VERSION);
        long now = Instant.now().getEpochSecond();
        root.put("saved_at", now);
        ArrayNode arr = root.putArray("proxies");
        for (ProxyInfo p : proxies) {
            ObjectNode e = arr.addObject();
            e.put("host", p.host());
            e.put("port", p.port());
            e.put("protocol", p.protocol().name().toLowerCase(java.util.Locale.ROOT));
            if (p.cachedScore() != null) {
                e.put("score", p.cachedScore());
            }
            if (p.latencyMs() != null) {
                e.put("latency_ms", p.latencyMs());
            }
            e.put("last_seen", now);
        }
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            MAPPER.writeValue(tmp.toFile(), root);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot save proxy cache {}: {}", path, e.toString());
        }
    }

    public void delete() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Cannot delete proxy cache {}: {}", path, e.toString());
        }
    }
}
