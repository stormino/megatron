package io.github.stormino.megatron;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.stormino.megatron.download.DownloadCancelledException;
import io.github.stormino.megatron.download.DownloadControl;
import io.github.stormino.megatron.download.DownloadException;
import io.github.stormino.megatron.download.DownloadListener;
import io.github.stormino.megatron.download.DownloadResult;
import io.github.stormino.megatron.download.DownloadSettings;
import io.github.stormino.megatron.download.ParallelDownloader;
import io.github.stormino.megatron.http.HttpClientFactory;
import io.github.stormino.megatron.mega.FolderExpander;
import io.github.stormino.megatron.mega.MegaApiClient;
import io.github.stormino.megatron.mega.MegaApiException;
import io.github.stormino.megatron.mega.MegaLinks;
import io.github.stormino.megatron.proxy.PoolRefresher;
import io.github.stormino.megatron.proxy.ProxyCache;
import io.github.stormino.megatron.proxy.ProxyHarvester;
import io.github.stormino.megatron.proxy.ProxyInfo;
import io.github.stormino.megatron.proxy.ProxyPool;
import io.github.stormino.megatron.proxy.ProxyProtocol;
import io.github.stormino.megatron.proxy.ProxyScraper;
import io.github.stormino.megatron.proxy.ProxySource;
import io.github.stormino.megatron.proxy.ProxySources;
import io.github.stormino.megatron.proxy.ProxyValidator;
import io.github.stormino.megatron.util.Threads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * Entry point of the library: downloads public Mega files and folders through a self-maintaining
 * pool of free proxies, one proxy per chunk.
 *
 * <pre>{@code
 * try (Megatron megatron = Megatron.builder().cacheFile(Path.of("proxy-cache.json")).build()) {
 *     DownloadResult r = megatron.download("https://mega.nz/file/XXXX#YYYY", Path.of("downloads"));
 * }
 * }</pre>
 *
 * <p>The pool is filled lazily on first use (cache first, then scrape + validate) and kept healthy
 * by a background refresher. Instances are thread-safe; {@link #close()} stops the refresher and
 * saves the proxy cache.
 */
public final class Megatron implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Megatron.class);
    private static final Set<Integer> DEFINITIVE_API_CODES = Set.of(-2, -9, -11, -14, -16);

    private final HttpClientFactory http;
    private final ProxyPool pool;
    private final ProxyValidator validator;
    private final ProxyCache cache;
    private final PoolRefresher refresher;
    private final MegaApiClient api;
    private final ParallelDownloader downloader;
    private final DownloadSettings downloadSettings;
    private final List<ProxyInfo> staticProxies;
    private final int maxConcurrentFiles;
    private final int fileAttempts;
    private final int maxFolderFiles;
    private final Object warmUpLock = new Object();
    private volatile boolean warmedUp;
    private volatile boolean closed;

    private Megatron(Builder b) {
        this.http = new HttpClientFactory(b.userAgent);
        this.downloadSettings = b.downloadSettings;
        this.staticProxies = List.copyOf(b.staticProxies);
        this.maxConcurrentFiles = b.maxConcurrentFiles;
        this.fileAttempts = b.fileAttempts;
        this.maxFolderFiles = b.maxFolderFiles;
        this.validator = new ProxyValidator(http, b.validatorSettings);
        ProxyHarvester harvester = null;
        if (staticProxies.isEmpty()) {
            harvester = new ProxyHarvester(new ProxyScraper(http, b.sources), validator, b.maxCandidates);
            harvester.progressListener(b.validationProgress);
        }
        this.pool = new ProxyPool(harvester, b.poolSettings, System::nanoTime);
        this.pool.selectionMode(b.selectionMode);
        this.pool.connections(downloadSettings.connectionsPerFile());
        this.cache = b.cacheFile == null ? null : new ProxyCache(b.cacheFile, b.cacheTtl);
        this.refresher = harvester != null && b.refresherSettings != null
                ? new PoolRefresher(pool, b.refresherSettings) : null;
        int apiAttempts = downloadSettings.resolveViaProxy() ? 2 : 5;
        this.api = new MegaApiClient(http, b.apiUrl, Duration.ofSeconds(30), apiAttempts, () -> false);
        this.downloader = new ParallelDownloader(pool, http, api, downloadSettings);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Library version from the jar manifest, or {@code "dev"} when running from classes (IDE, tests). */
    public static String version() {
        String v = Megatron.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    public ProxyPool pool() {
        return pool;
    }

    public DownloadSettings downloadSettings() {
        return downloadSettings;
    }

    /**
     * Fills the pool (static proxies, or cache + scrape + validate) and starts the refresher.
     * Called automatically by the first download; call it explicitly to pay the cost up front.
     */
    public void warmUp() {
        ensureOpen();
        synchronized (warmUpLock) {
            if (warmedUp) {
                return;
            }
            if (!staticProxies.isEmpty()) {
                pool.addAll(staticProxies);
            } else {
                if (cache != null) {
                    List<ProxyInfo> cached = cache.load();
                    if (!cached.isEmpty()) {
                        pool.addAll(validator.validate(cached));
                    }
                }
                if (pool.size() == 0) {
                    pool.refillBlocking(false);
                }
                if (refresher != null) {
                    refresher.start();
                }
            }
            warmedUp = true;
            log.info("Megatron ready: {} proxies alive", pool.size());
        }
    }

    // ------------------------------------------------------------------ single file

    public DownloadResult download(String url, Path outputDir) {
        return download(url, outputDir, DownloadListener.NONE, new DownloadControl());
    }

    /**
     * Downloads a file link (or folder job URL) into {@code outputDir}, retrying the whole file up
     * to {@code fileAttempts} times; each retry resumes from the chunks already on disk.
     */
    public DownloadResult download(String url, Path outputDir, DownloadListener listener, DownloadControl control) {
        warmUp();
        DownloadListener l = listener == null ? DownloadListener.NONE : listener;
        DownloadControl c = control == null ? new DownloadControl() : control;
        RuntimeException last = null;
        for (int attempt = 1; attempt <= fileAttempts; attempt++) {
            try {
                DownloadResult r = downloader.download(url, outputDir, l, c);
                l.onCompleted(r);
                return r;
            } catch (DownloadCancelledException e) {
                l.onFailed(url, e);
                throw e;
            } catch (MegaApiException e) {
                if (DEFINITIVE_API_CODES.contains(e.apiCode()) || e.apiCode() == 0 && !MegaLinks.isDownloadable(url)) {
                    l.onFailed(url, e);
                    throw e;
                }
                last = e;
            } catch (DownloadException e) {
                last = e;
            }
            log.warn("Attempt {}/{} for {} failed: {}", attempt, fileAttempts, url, last.getMessage());
        }
        l.onFailed(url, last);
        throw last;
    }

    // ------------------------------------------------------------------ folders

    /** Lists a folder link and returns its files as self-contained jobs. */
    public FolderExpander.FolderExpansion expandFolder(String folderUrl) {
        MegaLinks.FolderLink link = MegaLinks.parseFolder(folderUrl)
                .orElseThrow(() -> new MegaApiException("Not a Mega folder link: " + folderUrl));
        warmUp();
        MegaApiClient lister = api.withTimeout(Duration.ofSeconds(60));
        List<JsonNode> nodes = withProxyRotation(proxy -> lister.listFolder(link.folderId(), proxy));
        return FolderExpander.expand(link, nodes, maxFolderFiles);
    }

    // ------------------------------------------------------------------ batches

    /**
     * Downloads many links, expanding folder links into their files. Folder contents are saved as a
     * tree under {@code outputDir/<folder name>/...}. Up to {@code maxConcurrentFiles} files run at
     * the same time. Failures do not stop the batch (cancellation does).
     */
    public BatchResult downloadAll(List<String> urls, Path outputDir, DownloadListener listener,
                                   DownloadControl control) {
        DownloadControl c = control == null ? new DownloadControl() : control;
        Map<String, Throwable> failed = Collections.synchronizedMap(new LinkedHashMap<>());
        Map<String, Path> jobs = new LinkedHashMap<>();
        for (String url : urls) {
            if (MegaLinks.isFolderLink(url)) {
                try {
                    FolderExpander.FolderExpansion exp = expandFolder(url);
                    for (FolderExpander.FolderFile f : exp.files()) {
                        Path dir = outputDir;
                        for (String seg : f.relPath().subList(0, f.relPath().size() - 1)) {
                            dir = dir.resolve(seg);
                        }
                        jobs.putIfAbsent(f.jobUrl(), dir);
                    }
                } catch (RuntimeException e) {
                    failed.put(url, e);
                }
            } else {
                jobs.putIfAbsent(url, outputDir);
            }
        }
        List<DownloadResult> completed = Collections.synchronizedList(new ArrayList<>());
        ExecutorService exec = Executors.newFixedThreadPool(Math.max(1, Math.min(maxConcurrentFiles, jobs.size())),
                Threads.daemonFactory("megatron-file"));
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (var job : jobs.entrySet()) {
                futures.add(exec.submit(() -> {
                    if (c.isCancelled()) {
                        failed.put(job.getKey(), new DownloadCancelledException("cancelled"));
                        return;
                    }
                    try {
                        completed.add(download(job.getKey(), job.getValue(), listener, c));
                    } catch (RuntimeException e) {
                        failed.put(job.getKey(), e);
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    log.warn("Unexpected batch failure", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    c.cancel();
                    break;
                }
            }
        } finally {
            exec.shutdown();
        }
        return new BatchResult(completed, failed);
    }

    // ------------------------------------------------------------------ helpers

    private <T> T withProxyRotation(Function<ProxyInfo, T> call) {
        if (!downloadSettings.resolveViaProxy()) {
            return call.apply(null);
        }
        RuntimeException last = null;
        for (int i = 0; i < downloadSettings.resolveAttempts(); i++) {
            ProxyInfo proxy = pool.next();
            if (proxy == null) {
                pool.refillBlocking(false);
                proxy = pool.next();
            }
            if (proxy == null) {
                throw new DownloadException("No working proxy available");
            }
            try {
                return call.apply(proxy);
            } catch (MegaApiException e) {
                if (DEFINITIVE_API_CODES.contains(e.apiCode())) {
                    throw e;
                }
                pool.penalize(proxy, false);
                last = e;
            }
        }
        throw new DownloadException("Mega API unreachable through " + downloadSettings.resolveAttempts()
                + " proxies", last);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Megatron is closed");
        }
    }

    /** Stops the refresher and saves good proxies to the cache file (if configured). */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (refresher != null) {
            refresher.close();
        }
        if (cache != null && warmedUp) {
            cache.save(pool.exportForCache(0));
        }
    }

    /** Configuration of a {@link Megatron} instance. */
    public static final class Builder {
        private List<ProxySource> sources = ProxySources.defaults(ProxyProtocol.HTTP, ProxyProtocol.SOCKS5);
        private final List<ProxyInfo> staticProxies = new ArrayList<>();
        private Path cacheFile;
        private Duration cacheTtl = Duration.ofHours(6);
        private ProxyValidator.Settings validatorSettings = ProxyValidator.Settings.defaults();
        private ProxyValidator.ProgressListener validationProgress;
        private ProxyPool.Settings poolSettings = ProxyPool.Settings.defaults();
        private ProxyPool.SelectionMode selectionMode = ProxyPool.SelectionMode.SCORE;
        private PoolRefresher.Settings refresherSettings = PoolRefresher.Settings.defaults();
        private DownloadSettings downloadSettings = DownloadSettings.defaults();
        private int maxCandidates = 12_000;
        private int maxConcurrentFiles = 1;
        private int fileAttempts = 3;
        private int maxFolderFiles = 500;
        private String userAgent = HttpClientFactory.DEFAULT_USER_AGENT;
        private String apiUrl = MegaApiClient.DEFAULT_API_URL;

        /** Proxy lists to scrape (default: built-in HTTP and SOCKS5 lists). */
        public Builder sources(List<ProxySource> sources) {
            this.sources = List.copyOf(sources);
            return this;
        }

        /**
         * Uses exactly these proxies instead of scraping public lists (no validation, no refresher).
         * Handy with a paid/private proxy list.
         */
        public Builder proxies(List<ProxyInfo> proxies) {
            this.staticProxies.addAll(proxies);
            return this;
        }

        /** JSON file where good proxies are saved on close and reloaded on the next start. */
        public Builder cacheFile(Path cacheFile) {
            this.cacheFile = cacheFile;
            return this;
        }

        public Builder cacheTtl(Duration ttl) {
            this.cacheTtl = ttl;
            return this;
        }

        public Builder validatorSettings(ProxyValidator.Settings settings) {
            this.validatorSettings = settings;
            return this;
        }

        /** Receives validation progress (useful for UIs: the first refill can take a few minutes). */
        public Builder validationProgress(ProxyValidator.ProgressListener listener) {
            this.validationProgress = listener;
            return this;
        }

        public Builder poolSettings(ProxyPool.Settings settings) {
            this.poolSettings = settings;
            return this;
        }

        /**
         * {@link ProxyPool.SelectionMode#THROUGHPUT} prefers the fastest proxies observed; pair it with
         * {@code validatorSettings(...withSpeedTest(true))} to measure them up front.
         */
        public Builder selectionMode(ProxyPool.SelectionMode mode) {
            this.selectionMode = mode;
            return this;
        }

        /** Background refresher settings; {@code null} disables it. */
        public Builder refresher(PoolRefresher.Settings settings) {
            this.refresherSettings = settings;
            return this;
        }

        public Builder downloadSettings(DownloadSettings settings) {
            this.downloadSettings = settings;
            return this;
        }

        /** Cap of scraped candidates validated per refill. */
        public Builder maxCandidates(int maxCandidates) {
            this.maxCandidates = maxCandidates;
            return this;
        }

        /** Files downloaded at the same time by {@link #downloadAll}. */
        public Builder maxConcurrentFiles(int n) {
            this.maxConcurrentFiles = Math.max(1, n);
            return this;
        }

        /** Whole-file attempts (each resumes the previous one). */
        public Builder fileAttempts(int n) {
            this.fileAttempts = Math.max(1, n);
            return this;
        }

        /** Maximum files taken from one folder link (0 = unlimited). */
        public Builder maxFolderFiles(int n) {
            this.maxFolderFiles = Math.max(0, n);
            return this;
        }

        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        /** Override of Mega's API endpoint (tests). */
        public Builder apiUrl(String apiUrl) {
            this.apiUrl = apiUrl;
            return this;
        }

        public Megatron build() {
            return new Megatron(this);
        }
    }
}
