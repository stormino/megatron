package io.github.stormino.megatron.proxy;

import io.github.stormino.megatron.http.HttpClientFactory;
import io.github.stormino.megatron.util.Threads;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;

/**
 * Multi-stage proxy validation:
 * <ol>
 *   <li><b>alive</b>: fast pre-filter on a lightweight connectivity endpoint (high concurrency,
 *       short timeout). Measures latency.</li>
 *   <li><b>mega</b>: can the proxy reach Mega's API host? Any HTTPS response counts as success.
 *       Each passing proxy then gets its exit IP measured (an IP echo service over HTTPS); proxies
 *       exiting from our own IP are transparent and dropped. Stops early once {@code targetAlive}
 *       distinct exit IPs passed.</li>
 *   <li><b>speed</b> (optional): real download of a test file; proxies below the admission
 *       threshold are dropped, the others get a pre-measured throughput.</li>
 * </ol>
 */
public final class ProxyValidator {

    private static final Logger log = LoggerFactory.getLogger(ProxyValidator.class);

    /** Validation tunables. */
    /**
     * @param targetAlive  early stop of the Mega stage after this many distinct exit IPs (0 = test all)
     * @param exitIpCheck  measure each proxy's exit IP (pool grouping) and drop transparent proxies
     * @param exitIpUrl    HTTPS endpoint answering with the caller's IP as plain text
     */
    public record Settings(String aliveUrl, Duration aliveTimeout, int aliveWorkers,
                           String megaUrl, Duration megaTimeout, int megaWorkers, int targetAlive,
                           boolean speedTest, String speedTestUrl, long speedTestBytes, Duration speedTestTimeout,
                           int speedTestWorkers, long speedAdmissionBps,
                           boolean exitIpCheck, String exitIpUrl, Duration exitIpTimeout) {
        public static Settings defaults() {
            return new Settings(
                    "http://www.gstatic.com/generate_204", Duration.ofSeconds(4), 200,
                    "https://g.api.mega.co.nz/cs", Duration.ofSeconds(8), 120, 300,
                    false, "http://speedtest.tele2.net/1MB.zip", 1024 * 1024, Duration.ofSeconds(15), 30,
                    100 * 1024,
                    true, "https://api.ipify.org", Duration.ofSeconds(8));
        }

        public Settings withSpeedTest(boolean enabled) {
            return new Settings(aliveUrl, aliveTimeout, aliveWorkers, megaUrl, megaTimeout, megaWorkers, targetAlive,
                    enabled, speedTestUrl, speedTestBytes, speedTestTimeout, speedTestWorkers, speedAdmissionBps,
                    exitIpCheck, exitIpUrl, exitIpTimeout);
        }

        public Settings withTargetAlive(int target) {
            return new Settings(aliveUrl, aliveTimeout, aliveWorkers, megaUrl, megaTimeout, megaWorkers, target,
                    speedTest, speedTestUrl, speedTestBytes, speedTestTimeout, speedTestWorkers, speedAdmissionBps,
                    exitIpCheck, exitIpUrl, exitIpTimeout);
        }

        public Settings withExitIpCheck(boolean enabled) {
            return new Settings(aliveUrl, aliveTimeout, aliveWorkers, megaUrl, megaTimeout, megaWorkers, targetAlive,
                    speedTest, speedTestUrl, speedTestBytes, speedTestTimeout, speedTestWorkers, speedAdmissionBps,
                    enabled, exitIpUrl, exitIpTimeout);
        }

        /** Same settings with every endpoint replaced (tests, private mirrors). */
        public Settings withUrls(String alive, String mega, String exitIp) {
            return new Settings(alive, aliveTimeout, aliveWorkers, mega, megaTimeout, megaWorkers, targetAlive,
                    speedTest, speedTestUrl, speedTestBytes, speedTestTimeout, speedTestWorkers, speedAdmissionBps,
                    exitIpCheck, exitIp, exitIpTimeout);
        }
    }

    /** Progress of a validation stage. */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String stage, int done, int total, int alive);
    }

    private static final java.util.regex.Pattern IP = java.util.regex.Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private final HttpClientFactory http;
    private final Settings settings;
    /** Our own public IP, to recognise transparent proxies; {@code null} if unknown. */
    private volatile String ownIp;

    public ProxyValidator(HttpClientFactory http) {
        this(http, Settings.defaults());
    }

    public ProxyValidator(HttpClientFactory http, Settings settings) {
        this.http = http;
        this.settings = settings;
    }

    /** Overrides the detected own public IP (tests, or callers that already know it). */
    void ownIp(String ip) {
        this.ownIp = ip;
    }

    public List<ProxyInfo> validate(List<ProxyInfo> candidates) {
        return validate(candidates, null);
    }

    public List<ProxyInfo> validate(List<ProxyInfo> candidates, ProgressListener progress) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        if (settings.exitIpCheck() && ownIp == null) {
            ownIp = fetchIp(null);
            log.info("Own public IP: {}", ownIp == null ? "unknown (transparent proxies cannot be detected)" : ownIp);
        }
        List<ProxyInfo> stage1 = runStage("alive", candidates, this::checkAlive, settings.aliveWorkers(), 0, progress);
        log.info("[alive] {}/{} proxies passed", stage1.size(), candidates.size());
        if (stage1.isEmpty()) {
            // The connectivity endpoint is probably down: fall back to testing everything against Mega.
            log.warn("[alive] no proxy passed; is {} down? Falling back to the Mega stage", settings.aliveUrl());
            stage1 = candidates;
        }
        List<ProxyInfo> stage2 = runStage("mega", stage1, this::checkMega, settings.megaWorkers(),
                settings.targetAlive(), progress);
        log.info("[mega] {}/{} proxies reach Mega, {} distinct exit IPs", stage2.size(), stage1.size(),
                stage2.stream().map(ProxyInfo::exitKey).distinct().count());
        if (!settings.speedTest() || stage2.isEmpty()) {
            return stage2;
        }
        List<ProxyInfo> stage3 = runStage("speed", stage2, this::checkSpeed, settings.speedTestWorkers(), 0, progress);
        log.info("[speed] {}/{} proxies above {} KB/s", stage3.size(), stage2.size(),
                settings.speedAdmissionBps() / 1024);
        return stage3;
    }

    private List<ProxyInfo> runStage(String name, List<ProxyInfo> proxies, Predicate<ProxyInfo> check,
                                     int workers, int targetAlive, ProgressListener progress) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(workers, proxies.size())),
                Threads.daemonFactory("megatron-validate-" + name));
        try {
            CompletionService<Boolean> cs = new ExecutorCompletionService<>(pool);
            java.util.Map<Future<Boolean>, ProxyInfo> owner = new java.util.HashMap<>();
            for (ProxyInfo p : proxies) {
                Future<Boolean> f = cs.submit(() -> check.test(p));
                owner.put(f, p);
            }
            List<ProxyInfo> alive = new ArrayList<>();
            java.util.Set<String> exits = new java.util.HashSet<>();
            for (int done = 1; done <= proxies.size(); done++) {
                Future<Boolean> f;
                try {
                    f = cs.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                boolean ok;
                try {
                    ok = f.get();
                } catch (ExecutionException e) {
                    ok = false;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (ok) {
                    ProxyInfo p = owner.get(f);
                    alive.add(p);
                    exits.add(p.exitKey());
                }
                if (progress != null) {
                    progress.onProgress(name, done, proxies.size(), alive.size());
                }
                if (targetAlive > 0 && exits.size() >= targetAlive) {
                    log.info("[{}] target of {} exit IPs reached after {}/{}, stopping early", name, targetAlive,
                            done, proxies.size());
                    break;
                }
            }
            return alive;
        } finally {
            pool.shutdownNow();
        }
    }

    boolean checkAlive(ProxyInfo proxy) {
        long t0 = System.nanoTime();
        Request req = new Request.Builder().url(settings.aliveUrl()).header("User-Agent", http.userAgent()).build();
        // Timeouts are per phase (connect, read), not a cap on the whole call: free proxies are
        // slow to tunnel and handshake, and a total cap rejected about half of the usable ones.
        try (Response resp = http.forProxy(proxy, settings.aliveTimeout(), settings.aliveTimeout(),
                Duration.ZERO, false).newCall(req).execute()) {
            boolean ok = resp.code() >= 200 && resp.code() < 400;
            if (ok) {
                proxy.latencyMs((int) ((System.nanoTime() - t0) / 1_000_000));
            }
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    boolean checkMega(ProxyInfo proxy) {
        if (!reachesMega(proxy)) {
            return false;
        }
        if (!settings.exitIpCheck()) {
            return true;
        }
        String exit = fetchIp(proxy);
        proxy.exitIp(exit);
        if (exit != null && exit.equals(ownIp)) {
            log.debug("{} is transparent (exits from our own IP): dropped", proxy);
            return false;
        }
        return true;
    }

    /** Public IP seen by the echo service through {@code proxy} ({@code null} = direct), or {@code null}. */
    String fetchIp(ProxyInfo proxy) {
        Request req = new Request.Builder().url(settings.exitIpUrl()).header("User-Agent", http.userAgent()).build();
        try (Response resp = http.forProxy(proxy, settings.exitIpTimeout(), settings.exitIpTimeout(),
                Duration.ZERO, false).newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                return null;
            }
            String text = resp.body().string().strip();
            return IP.matcher(text).matches() ? text : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean reachesMega(ProxyInfo proxy) {
        Request req = new Request.Builder().url(settings.megaUrl()).header("User-Agent", http.userAgent()).build();
        try (Response ignored = http.forProxy(proxy, settings.megaTimeout(), settings.megaTimeout(),
                Duration.ZERO, false).newCall(req).execute()) {
            // HTTPS: any response (even a Mega application error) proves the CONNECT tunnel works and
            // the TLS certificate is Mega's. Do not switch this to plain HTTP: most free proxies
            // answer plain requests with their own 4xx pages, which would count as false positives.
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    boolean checkSpeed(ProxyInfo proxy) {
        String url = settings.speedTestUrl() + (settings.speedTestUrl().contains("?") ? "&" : "?")
                + "nc=" + UUID.randomUUID().toString().replace("-", "");
        Request req = new Request.Builder().url(url).header("User-Agent", http.userAgent()).build();
        long tRequest = System.nanoTime();
        try (Response resp = http.forProxy(proxy, settings.speedTestTimeout(), settings.speedTestTimeout(),
                settings.speedTestTimeout(), true).newCall(req).execute()) {
            ResponseBody body = resp.body();
            if (resp.code() != 200 || body == null) {
                return false;
            }
            long tFirst = -1;
            long read = 0;
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = body.byteStream()) {
                int n;
                while (read < settings.speedTestBytes() && (n = in.read(buf)) != -1) {
                    if (tFirst < 0) {
                        tFirst = System.nanoTime();
                    }
                    read += n;
                }
            }
            if (tFirst < 0 || read == 0) {
                return false;
            }
            double bps = sustainedThroughput(read, tRequest, tFirst, System.nanoTime());
            if (bps < settings.speedAdmissionBps()) {
                return false;
            }
            proxy.throughputBps(bps);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Sustained throughput in bytes/s. Times the body window only (excluding connect/TLS/TTFB),
     * but falls back to the full window when the body arrived in one burst from a buffering proxy,
     * which would otherwise yield impossible values.
     */
    public static double sustainedThroughput(long bytes, long tRequestNanos, long tFirstNanos, long tLastNanos) {
        if (bytes <= 0) {
            return 0;
        }
        double minWindow = 0.05;
        double body = (tLastNanos - tFirstNanos) / 1e9;
        if (body >= minWindow) {
            return bytes / body;
        }
        double full = (tLastNanos - tRequestNanos) / 1e9;
        return bytes / Math.max(full, minWindow);
    }
}
