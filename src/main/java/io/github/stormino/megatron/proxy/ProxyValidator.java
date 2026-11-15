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
 *   <li><b>mega</b>: can the proxy reach Mega's API host? Any HTTP response counts as success.
 *       Stops early once {@code targetAlive} proxies passed.</li>
 *   <li><b>speed</b> (optional): real download of a test file; proxies below the admission
 *       threshold are dropped, the others get a pre-measured throughput.</li>
 * </ol>
 */
public final class ProxyValidator {

    private static final Logger log = LoggerFactory.getLogger(ProxyValidator.class);

    /** Validation tunables. */
    public record Settings(String aliveUrl, Duration aliveTimeout, int aliveWorkers,
                           String megaUrl, Duration megaTimeout, int megaWorkers, int targetAlive,
                           boolean speedTest, String speedTestUrl, long speedTestBytes, Duration speedTestTimeout,
                           int speedTestWorkers, long speedAdmissionBps) {
        public static Settings defaults() {
            return new Settings(
                    "http://www.gstatic.com/generate_204", Duration.ofSeconds(4), 200,
                    "https://g.api.mega.co.nz/cs", Duration.ofSeconds(8), 120, 300,
                    false, "http://speedtest.tele2.net/1MB.zip", 1024 * 1024, Duration.ofSeconds(15), 30,
                    100 * 1024);
        }

        public Settings withSpeedTest(boolean enabled) {
            return new Settings(aliveUrl, aliveTimeout, aliveWorkers, megaUrl, megaTimeout, megaWorkers, targetAlive,
                    enabled, speedTestUrl, speedTestBytes, speedTestTimeout, speedTestWorkers, speedAdmissionBps);
        }

        public Settings withTargetAlive(int target) {
            return new Settings(aliveUrl, aliveTimeout, aliveWorkers, megaUrl, megaTimeout, megaWorkers, target,
                    speedTest, speedTestUrl, speedTestBytes, speedTestTimeout, speedTestWorkers, speedAdmissionBps);
        }
    }

    /** Progress of a validation stage. */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String stage, int done, int total, int alive);
    }

    private final HttpClientFactory http;
    private final Settings settings;

    public ProxyValidator(HttpClientFactory http) {
        this(http, Settings.defaults());
    }

    public ProxyValidator(HttpClientFactory http, Settings settings) {
        this.http = http;
        this.settings = settings;
    }

    public List<ProxyInfo> validate(List<ProxyInfo> candidates) {
        return validate(candidates, null);
    }

    public List<ProxyInfo> validate(List<ProxyInfo> candidates, ProgressListener progress) {
        if (candidates.isEmpty()) {
            return List.of();
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
        log.info("[mega] {}/{} proxies reach Mega", stage2.size(), stage1.size());
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
                    alive.add(owner.get(f));
                }
                if (progress != null) {
                    progress.onProgress(name, done, proxies.size(), alive.size());
                }
                if (targetAlive > 0 && alive.size() >= targetAlive) {
                    log.info("[{}] target {} reached after {}/{}, stopping early", name, targetAlive, done,
                            proxies.size());
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
        try (Response resp = http.forProxy(proxy, settings.aliveTimeout(), settings.aliveTimeout(),
                settings.aliveTimeout(), false).newCall(req).execute()) {
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
        Request req = new Request.Builder().url(settings.megaUrl()).header("User-Agent", http.userAgent()).build();
        try (Response ignored = http.forProxy(proxy, settings.megaTimeout(), settings.megaTimeout(),
                settings.megaTimeout(), false).newCall(req).execute()) {
            // Any response (even a Mega application error) proves the round trip reached Mega.
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
