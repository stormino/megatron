package io.github.stormino.megatron.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stormino.megatron.http.HttpClientFactory;
import io.github.stormino.megatron.util.Threads;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads public proxy lists and normalises them into {@link ProxyInfo}, de-duplicated by
 * {@code host:port} (first source wins).
 */
public final class ProxyScraper {

    private static final Logger log = LoggerFactory.getLogger(ProxyScraper.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern PLAIN_LINE = Pattern.compile(
            "^(?:[a-zA-Z0-9+]+://)?(?<host>[A-Za-z0-9.\\-]+):(?<port>\\d{2,5})(?:[/:?#\\s].*)?$");

    /** ProxyScrape pre-filter: drop entries the source itself reports as unreliable. */
    static final double PREFILTER_MIN_UPTIME = 50.0;
    static final double PREFILTER_MAX_LATENCY_MS = 3000;

    private final HttpClientFactory http;
    private final List<ProxySource> sources;
    private final Duration timeout;
    private final int parallelism;

    public ProxyScraper(HttpClientFactory http, List<ProxySource> sources) {
        this(http, sources, Duration.ofSeconds(8), 16);
    }

    public ProxyScraper(HttpClientFactory http, List<ProxySource> sources, Duration timeout, int parallelism) {
        this.http = http;
        this.sources = List.copyOf(sources);
        this.timeout = timeout;
        this.parallelism = Math.max(1, parallelism);
    }

    /** Fetches every source (in parallel) and returns the de-duplicated union. */
    public List<ProxyInfo> fetchAll() {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(parallelism, Math.max(1, sources.size())),
                Threads.daemonFactory("megatron-scraper"));
        try {
            List<Future<List<ProxyInfo>>> futures = new ArrayList<>();
            for (ProxySource source : sources) {
                futures.add(pool.submit(() -> fetchSource(source)));
            }
            Map<String, ProxyInfo> seen = new LinkedHashMap<>();
            for (int i = 0; i < futures.size(); i++) {
                ProxySource source = sources.get(i);
                try {
                    List<ProxyInfo> found = futures.get(i).get();
                    int added = 0;
                    for (ProxyInfo p : found) {
                        if (seen.putIfAbsent(p.key(), p) == null) {
                            added++;
                        }
                    }
                    log.debug("Source '{}': {} proxies, {} new after dedup", source.name(), found.size(), added);
                } catch (ExecutionException e) {
                    log.warn("Source '{}' failed: {}", source.name(), e.getCause().toString());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.info("Scraped {} unique proxies from {} sources", seen.size(), sources.size());
            return new ArrayList<>(seen.values());
        } finally {
            pool.shutdownNow();
        }
    }

    List<ProxyInfo> fetchSource(ProxySource source) throws IOException {
        Duration t = source.kind() == SourceKind.PROXYSCRAPE_JSON ? Duration.ofSeconds(30) : timeout;
        OkHttpClient client = http.direct(t, t);
        Request request = new Request.Builder().url(source.url()).header("User-Agent", http.userAgent()).build();
        try (Response resp = client.newCall(request).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code());
            }
            String body = resp.body() == null ? "" : resp.body().string();
            return parse(source, body);
        }
    }

    /** Parses a source body. The source's protocol overrides whatever the format says. */
    public static List<ProxyInfo> parse(ProxySource source, String body) {
        List<HostPort> raw = switch (source.kind()) {
            case HTML_TABLE -> parseHtmlTable(body);
            case PLAIN_TEXT -> parsePlainText(body);
            case GEONODE_JSON -> parseGeonodeJson(body);
            case JSONL -> parseJsonl(body);
            case DATABAY_JSON -> parseDatabayJson(body);
            case PROXYSCRAPE_JSON -> parseProxyscrapeJson(body);
        };
        List<ProxyInfo> out = new ArrayList<>(raw.size());
        for (HostPort hp : raw) {
            try {
                ProxyInfo p = new ProxyInfo(hp.host, hp.port, source.protocol(), null, null, source.name());
                p.latencyMs(hp.latencyMs);
                p.uptimePercent(hp.uptime);
                out.add(p);
            } catch (IllegalArgumentException ignored) {
                // invalid port or host: skip the entry
            }
        }
        return out;
    }

    record HostPort(String host, int port, Integer latencyMs, Double uptime) {
        HostPort(String host, int port) {
            this(host, port, null, null);
        }
    }

    static List<HostPort> parsePlainText(String text) {
        List<HostPort> out = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Matcher m = PLAIN_LINE.matcher(line);
            if (m.matches()) {
                out.add(new HostPort(m.group("host"), Integer.parseInt(m.group("port"))));
            }
        }
        return out;
    }

    static List<HostPort> parseHtmlTable(String html) {
        List<HostPort> out = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        Element table = doc.selectFirst("table");
        if (table == null) {
            return out;
        }
        for (Element row : table.select("tr")) {
            var cells = row.select("td");
            if (cells.size() < 2) {
                continue;
            }
            String host = cells.get(0).text().strip();
            String port = cells.get(1).text().strip();
            if (!host.isEmpty() && isPort(port)) {
                out.add(new HostPort(host, Integer.parseInt(port)));
            }
        }
        return out;
    }

    static List<HostPort> parseGeonodeJson(String text) {
        List<HostPort> out = new ArrayList<>();
        JsonNode root = readJson(text);
        if (root == null) {
            return out;
        }
        for (JsonNode e : root.path("data")) {
            addIfValid(out, e.path("ip").asText(null), e.path("port").asText(""), null, null);
        }
        return out;
    }

    static List<HostPort> parseJsonl(String text) {
        List<HostPort> out = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            JsonNode e = readJson(raw.strip());
            if (e == null || !e.isObject()) {
                continue;
            }
            String type = e.path("type").asText("");
            if (!type.equals("http") && !type.equals("https")) {
                continue;
            }
            addIfValid(out, e.path("host").asText(null), e.path("port").asText(""), null, null);
        }
        return out;
    }

    static List<HostPort> parseDatabayJson(String text) {
        List<HostPort> out = new ArrayList<>();
        JsonNode root = readJson(text);
        if (root == null) {
            return out;
        }
        JsonNode entries = root.isArray() ? root
                : root.hasNonNull("data") ? root.get("data") : root.path("proxies");
        for (JsonNode e : entries) {
            String host = e.hasNonNull("ip") ? e.get("ip").asText() : e.path("host").asText(null);
            Integer latency = e.path("latency_ms").isNumber() ? e.get("latency_ms").asInt() : null;
            addIfValid(out, host, e.path("port").asText(""), latency, null);
        }
        return out;
    }

    static List<HostPort> parseProxyscrapeJson(String text) {
        List<HostPort> out = new ArrayList<>();
        JsonNode root = readJson(text);
        if (root == null || !root.isArray()) {
            return out;
        }
        for (JsonNode e : root) {
            Double uptime = e.path("uptime_percent").isNumber() ? e.get("uptime_percent").asDouble() : null;
            Double latency = e.path("latency_ms").isNumber() ? e.get("latency_ms").asDouble() : null;
            if (uptime != null && uptime < PREFILTER_MIN_UPTIME) {
                continue;
            }
            if (latency != null && latency > PREFILTER_MAX_LATENCY_MS) {
                continue;
            }
            addIfValid(out, e.path("ip").asText(null), e.path("port").asText(""),
                    latency == null ? null : latency.intValue(), uptime);
        }
        return out;
    }

    private static void addIfValid(List<HostPort> out, String host, String port, Integer latency, Double uptime) {
        if (host != null && !host.isBlank() && isPort(port)) {
            out.add(new HostPort(host.strip(), Integer.parseInt(port), latency, uptime));
        }
    }

    private static boolean isPort(String s) {
        if (s == null || s.isEmpty() || s.length() > 5) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        int p = Integer.parseInt(s);
        return p >= 1 && p <= 65535;
    }

    private static JsonNode readJson(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            return null;
        }
    }
}
