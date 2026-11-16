package io.github.stormino.megatron.download;

import io.github.stormino.megatron.http.HttpClientFactory;
import io.github.stormino.megatron.mega.MegaApiClient;
import io.github.stormino.megatron.mega.MegaApiException;
import io.github.stormino.megatron.mega.MegaCrypto;
import io.github.stormino.megatron.mega.MegaLinks;
import io.github.stormino.megatron.mega.MegaMac;
import io.github.stormino.megatron.mega.ResolvedFile;
import io.github.stormino.megatron.proxy.ProxyInfo;
import io.github.stormino.megatron.proxy.ProxyPool;
import io.github.stormino.megatron.util.FileNames;
import io.github.stormino.megatron.util.Threads;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Downloads one Mega file as a queue of fixed-size chunks fetched by N parallel HTTP Range
 * connections, each through a different proxy taken from the pool.
 *
 * <ul>
 *   <li>Every chunk is decrypted on the fly (AES-CTR positioned at its offset) and written at its
 *       offset of a pre-allocated {@code <name>.part}; RAM stays constant for any file size.</li>
 *   <li>Each completed chunk is recorded in a sidecar, so a proxy dying costs at most one chunk and
 *       an interrupted download resumes where it stopped (independently of the connection count).</li>
 *   <li>CDN 403/509 (per-IP quota) benches the proxy and honours Retry-After; 503 kills the proxy and
 *       re-resolves the CDN URL; 429 retries the same proxy after a back-off.</li>
 *   <li>A watchdog kills attempts that are too slow or exceed their time budget.</li>
 *   <li>On success the {@code .part} is atomically renamed to the final name: the final name existing
 *       means the file is complete.</li>
 * </ul>
 */
public final class ParallelDownloader {

    private static final Logger log = LoggerFactory.getLogger(ParallelDownloader.class);
    private static final Set<Integer> RETRYABLE_API_CODES = Set.of(0, -3, -4, -6, -17, -18);

    private final ProxyPool pool;
    private final HttpClientFactory http;
    private final MegaApiClient api;
    private final DownloadSettings settings;

    public ParallelDownloader(ProxyPool pool, HttpClientFactory http, MegaApiClient api, DownloadSettings settings) {
        this.pool = pool;
        this.http = http;
        this.api = api;
        this.settings = settings;
    }

    public DownloadSettings settings() {
        return settings;
    }

    /**
     * Downloads a file link (public file link or folder job URL) into {@code outputDir}.
     *
     * @throws DownloadCancelledException if {@code control} was cancelled
     * @throws DownloadException          if the download failed; partial data is kept for resume
     * @throws MegaApiException           if Mega refused the link (not found, taken down, ...)
     */
    public DownloadResult download(String url, Path outputDir, DownloadListener listener, DownloadControl control) {
        return download(url, outputDir, null, listener, control);
    }

    /**
     * Like {@link #download(String, Path, DownloadListener, DownloadControl)}, saving the file as
     * {@code fileName} (sanitized) instead of the name stored on Mega; {@code null} keeps Mega's name.
     */
    public DownloadResult download(String url, Path outputDir, String fileName, DownloadListener listener,
                                   DownloadControl control) {
        if (!MegaLinks.isDownloadable(url)) {
            throw new MegaApiException(MegaLinks.isFolderLink(url)
                    ? "Folder links must be expanded first: " + url
                    : "Not a downloadable Mega link: " + url);
        }
        DownloadListener l = listener == null ? DownloadListener.NONE : listener;
        DownloadControl c = control == null ? new DownloadControl() : control;
        return new Transfer(url, outputDir, fileName, l, c).run();
    }

    /** Resolves a link, rotating proxies on network failures. */
    ResolvedFile resolve(String url, java.util.function.BooleanSupplier abort) {
        MegaApiClient client = api.withAbort(abort);
        if (!settings.resolveViaProxy()) {
            return client.resolve(url, null);
        }
        RuntimeException last = null;
        for (int i = 0; i < settings.resolveAttempts(); i++) {
            if (abort.getAsBoolean()) {
                throw new StopException("resolve aborted");
            }
            ProxyInfo proxy = nextProxy();
            if (proxy == null) {
                throw new DownloadException("No working proxy available to resolve " + url);
            }
            try {
                return client.resolve(url, proxy);
            } catch (MegaApiException e) {
                if (!RETRYABLE_API_CODES.contains(e.apiCode()) || "Resolve cancelled".equals(e.getMessage())) {
                    throw e;
                }
                log.debug("Resolve via {} failed: {}", proxy, e.getMessage());
                pool.penalize(proxy, false);
                last = e;
            }
        }
        throw new DownloadException("Could not resolve " + url + " after " + settings.resolveAttempts()
                + " proxies", last);
    }

    private ProxyInfo nextProxy() {
        ProxyInfo p = pool.next();
        if (p == null) {
            pool.refillBlocking(false);
            p = pool.next();
        }
        return p;
    }

    /** Thrown inside workers when the transfer must stop (abort or cancellation). */
    private static final class StopException extends RuntimeException {
        StopException(String message) {
            super(message, null, false, false);
        }
    }

    /** Per-file state. */
    private final class Transfer {
        private final String url;
        private final Path outputDir;
        private final String targetName;
        private final DownloadListener listener;
        private final DownloadControl control;
        private final AtomicBoolean abort = new AtomicBoolean();
        private final AtomicLong bytesDone = new AtomicLong();
        private final Object cdnLock = new Object();
        private final Set<Chunk> done = new HashSet<>();

        private volatile String cdnUrl;
        private ResolvedFile file;
        private Path part;

        Transfer(String url, Path outputDir, String targetName, DownloadListener listener, DownloadControl control) {
            this.url = url;
            this.outputDir = outputDir;
            this.targetName = targetName;
            this.listener = listener;
            this.control = control;
        }

        boolean shouldStop() {
            return abort.get() || control.isCancelled();
        }

        DownloadResult run() {
            long t0 = System.nanoTime();
            try {
                Files.createDirectories(outputDir);
            } catch (IOException e) {
                throw new DownloadException("Cannot create " + outputDir, e);
            }
            try {
                file = resolve(url, this::shouldStop);
            } catch (StopException e) {
                throw new DownloadCancelledException("Cancelled while resolving " + url);
            }
            cdnUrl = file.cdnUrl();
            String name = targetName == null || targetName.isBlank()
                    ? file.fileName() : FileNames.sanitizeFileName(targetName, file.fileName());
            Path finalPath = outputDir.resolve(name).normalize();
            if (!outputDir.toAbsolutePath().normalize().equals(finalPath.toAbsolutePath().getParent())) {
                throw new DownloadException("Refusing unsafe file name: " + name);
            }
            part = finalPath.resolveSibling(finalPath.getFileName() + ".part");
            log.info("Resolved {} -> {} ({} bytes)", url, file.fileName(), file.size());
            listener.onResolved(url, name, file.size(), finalPath);

            if (settings.skipExisting() && Files.isRegularFile(finalPath) && !Files.exists(part)
                    && sizeOf(finalPath) == file.size()) {
                log.info("{} already complete, skipping", finalPath);
                return new DownloadResult(url, file.handle(), finalPath, file.size(), file.size(),
                        Duration.ofNanos(System.nanoTime() - t0), true);
            }
            if (file.size() == 0) {
                try {
                    Files.deleteIfExists(part);
                    ProgressSidecar.delete(part);
                    Files.write(finalPath, new byte[0]);
                } catch (IOException e) {
                    throw new DownloadException("Cannot write " + finalPath, e);
                }
                listener.onProgress(url, 0, 0, 0);
                return new DownloadResult(url, file.handle(), finalPath, 0, 0,
                        Duration.ofNanos(System.nanoTime() - t0), false);
            }

            List<Chunk> chunks = Chunk.split(file.size(), settings.chunkSize());
            prepareResume(chunks);
            long resumed = done.stream().mapToLong(Chunk::length).sum();
            bytesDone.set(resumed);
            List<Chunk> pending = chunks.stream().filter(ch -> !done.contains(ch)).toList();
            log.info("{}: {} chunks, {} already done", file.fileName(), chunks.size(), chunks.size() - pending.size());

            runChunks(pending, chunks.size());
            verifyIntegrity();

            ProgressSidecar.delete(part);
            moveIntoPlace(part, finalPath);
            listener.onProgress(url, file.size(), file.size(), 0);
            DownloadResult result = new DownloadResult(url, file.handle(), finalPath, file.size(), resumed,
                    Duration.ofNanos(System.nanoTime() - t0), false);
            log.info("Completed {} ({} bytes)", finalPath, file.size());
            return result;
        }

        /**
         * Mega's meta-MAC cannot tell which chunk is wrong, so on mismatch everything is discarded
         * and the next attempt starts from scratch.
         */
        private void verifyIntegrity() {
            if (!settings.verifyMac()) {
                return;
            }
            boolean ok;
            try {
                ok = MegaMac.verify(part, file.key());
            } catch (IOException e) {
                throw new DownloadException("Cannot verify " + part, e);
            }
            if (!ok) {
                ProgressSidecar.delete(part);
                try {
                    Files.deleteIfExists(part);
                } catch (IOException ignored) {
                    // best effort
                }
                throw new IntegrityException("MAC mismatch for " + file.fileName()
                        + ": downloaded data is corrupt, partial file discarded");
            }
            log.info("{}: MAC verified", file.fileName());
        }

        private void prepareResume(List<Chunk> chunks) {
            try {
                boolean partOk = Files.isRegularFile(part) && Files.size(part) == file.size();
                if (partOk) {
                    Set<Chunk> loaded = ProgressSidecar.load(part, file.handle(), file.size(), settings.chunkSize());
                    loaded.retainAll(new HashSet<>(chunks));
                    for (Chunk ch : chunks) {
                        if (loaded.contains(ch)) {
                            done.add(ch);
                        }
                    }
                } else {
                    ProgressSidecar.delete(part);
                }
                long existing = partOk ? file.size() : 0;
                long usable = Files.getFileStore(outputDir).getUsableSpace();
                long needed = file.size() - existing + settings.minFreeDiskBytes();
                if (usable < needed) {
                    throw new DownloadException("Not enough disk space in " + outputDir + ": need " + needed
                            + " bytes, " + usable + " available");
                }
                if (!partOk) {
                    try (RandomAccessFile raf = new RandomAccessFile(part.toFile(), "rw")) {
                        raf.setLength(file.size());
                    }
                }
            } catch (IOException e) {
                throw new DownloadException("Cannot prepare " + part, e);
            }
        }

        private void runChunks(List<Chunk> pending, int totalChunks) {
            if (pending.isEmpty()) {
                return;
            }
            int connections = file.size() < 2 * DownloadSettings.MIN_PARALLEL_BYTES ? 1
                    : Math.min(settings.connectionsPerFile(), pending.size());
            ExecutorService workers = Executors.newFixedThreadPool(connections,
                    Threads.daemonFactory("megatron-chunk"));
            ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(
                    Threads.daemonFactory("megatron-progress"));
            List<String> errors = new ArrayList<>();
            int failed = 0;
            try {
                startProgressTicker(ticker);
                CompletionService<Void> cs = new ExecutorCompletionService<>(workers);
                for (Chunk ch : pending) {
                    cs.submit(() -> {
                        downloadChunk(ch);
                        return null;
                    });
                }
                for (int i = 0; i < pending.size(); i++) {
                    try {
                        cs.take().get();
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof StopException) {
                            continue;
                        }
                        errors.add(cause.getMessage());
                        log.warn("{}: {}", file.fileName(), cause.getMessage());
                        if (++failed >= settings.maxFailedChunks() && abort.compareAndSet(false, true)) {
                            log.warn("{}: {} chunks failed, aborting the file", file.fileName(), failed);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        abort.set(true);
                        throw new DownloadCancelledException("Interrupted while downloading " + url);
                    }
                }
            } finally {
                ticker.shutdownNow();
                workers.shutdownNow();
            }
            if (control.isCancelled()) {
                throw new DownloadCancelledException("Download cancelled: " + url);
            }
            if (!errors.isEmpty()) {
                throw new DownloadException(errors.size() + "/" + totalChunks + " chunks failed for "
                        + file.fileName() + ": " + String.join("; ", errors.subList(0, Math.min(3, errors.size()))));
            }
        }

        private void startProgressTicker(ScheduledExecutorService ticker) {
            long period = Math.max(100, settings.progressInterval().toMillis());
            long[] last = {bytesDone.get(), System.nanoTime()};
            ticker.scheduleAtFixedRate(() -> {
                try {
                    long now = System.nanoTime();
                    long bytes = bytesDone.get();
                    double secs = (now - last[1]) / 1e9;
                    double speed = secs > 0 ? Math.max(0, bytes - last[0]) / secs : 0;
                    last[0] = bytes;
                    last[1] = now;
                    listener.onProgress(url, Math.min(bytes, file.size()), file.size(), speed);
                } catch (RuntimeException e) {
                    log.debug("Progress listener failed", e);
                }
            }, period, period, TimeUnit.MILLISECONDS);
        }

        private void downloadChunk(Chunk ch) {
            int attempt = 0;
            Exception last = null;
            ProxyInfo sticky = null;
            while (attempt < settings.chunkAttempts()) {
                checkStop();
                control.awaitIfPaused();
                checkStop();
                attempt++;
                ProxyInfo proxy = sticky != null ? sticky : nextProxy();
                sticky = null;
                if (proxy == null) {
                    last = new IOException("proxy pool is empty");
                    sleep(5);
                    continue;
                }
                listener.onChunkAttempt(url, ch.index(), attempt, proxy);
                String currentUrl = cdnUrl;
                long written = 0;
                long attemptStart = System.nanoTime();
                try {
                    Request req = new Request.Builder()
                            .url(currentUrl)
                            .header("User-Agent", http.userAgent())
                            .header("Range", "bytes=" + ch.start() + "-" + ch.end())
                            .header("Accept-Encoding", "identity")
                            .build();
                    OkHttpClient client = http.forProxy(proxy, settings.connectTimeout(), settings.readTimeout());
                    try (Response resp = client.newCall(req).execute()) {
                        int code = resp.code();
                        if (code == 403 || code == 509) {
                            // Per-IP transfer quota on this file: bench the proxy, keep its score.
                            pool.cooldown(proxy);
                            last = new IOException("CDN " + code + " (rate limit)");
                            listener.onChunkRetry(url, ch.index(), attempt, proxy, last.getMessage());
                            sleep(retryAfter(resp, exponentialBackoff(attempt)));
                            continue;
                        }
                        if (code == 503) {
                            pool.penalize(proxy, true);
                            last = new IOException("CDN 503 (overload or expired URL)");
                            listener.onChunkRetry(url, ch.index(), attempt, proxy, last.getMessage());
                            refreshCdnUrl(currentUrl);
                            sleep(exponentialBackoff(attempt));
                            continue;
                        }
                        if (code == 429) {
                            // Too many IPs on the same file: retry the same proxy after a pause.
                            sticky = proxy;
                            last = new IOException("CDN 429 (too many concurrent IPs)");
                            listener.onChunkRetry(url, ch.index(), attempt, proxy, last.getMessage());
                            double fallback = Math.min(settings.tooManyIpsBackoffMax().toMillis() / 1000.0,
                                    settings.tooManyIpsBackoff().toMillis() / 1000.0 * attempt);
                            sleep(retryAfter(resp, fallback));
                            continue;
                        }
                        if (!resp.isSuccessful()) {
                            throw new IOException("HTTP " + code);
                        }
                        ResponseBody body = resp.body();
                        if (body == null) {
                            throw new IOException("empty body");
                        }
                        if (code != 206 && body.contentLength() != ch.length()) {
                            throw new IOException("proxy ignored the Range header (HTTP " + code + ")");
                        }
                        Cipher cipher = MegaCrypto.ctrCipherAt(file.key(), ch.start());
                        try (FileChannel out = FileChannel.open(part, StandardOpenOption.WRITE);
                             InputStream in = body.byteStream()) {
                            byte[] buf = new byte[64 * 1024];
                            long windowStart = attemptStart;
                            long windowBytes = 0;
                            int n;
                            while ((n = in.read(buf)) != -1) {
                                checkStop();
                                if (n == 0) {
                                    continue;
                                }
                                if (written + n > ch.length()) {
                                    throw new IOException("received more bytes than requested");
                                }
                                byte[] plain = cipher.update(buf, 0, n);
                                ByteBuffer bb = ByteBuffer.wrap(plain);
                                long pos = ch.start() + written;
                                while (bb.hasRemaining()) {
                                    pos += out.write(bb, pos);
                                }
                                written += n;
                                windowBytes += n;
                                bytesDone.addAndGet(n);
                                long now = System.nanoTime();
                                long elapsed = now - attemptStart;
                                if (elapsed > settings.chunkMaxDuration().toNanos()) {
                                    throw new IOException("time budget of " + settings.chunkMaxDuration().toSeconds()
                                            + "s exceeded (" + written + "/" + ch.length() + " bytes)");
                                }
                                long window = now - windowStart;
                                if (settings.minThroughputBps() > 0 && elapsed >= settings.throughputGrace().toNanos()
                                        && window >= settings.throughputWindow().toNanos()) {
                                    double bps = windowBytes / (window / 1e9);
                                    if (bps < settings.minThroughputBps()) {
                                        throw new IOException(String.format("too slow: %.0f KB/s < %d KB/s",
                                                bps / 1024, settings.minThroughputBps() / 1024));
                                    }
                                    windowStart = now;
                                    windowBytes = 0;
                                }
                            }
                        }
                        if (written != ch.length()) {
                            throw new IOException("short read: " + written + "/" + ch.length() + " bytes");
                        }
                    }
                    markDone(ch);
                    long millis = (System.nanoTime() - attemptStart) / 1_000_000;
                    pool.recordSuccess(proxy);
                    if (millis > 0) {
                        pool.recordThroughput(proxy, written * 1000.0 / millis);
                    }
                    listener.onChunkCompleted(url, ch.index(), written, proxy, millis);
                    return;
                } catch (StopException e) {
                    bytesDone.addAndGet(-written);
                    throw e;
                } catch (Exception e) {
                    bytesDone.addAndGet(-written);
                    last = e;
                    log.debug("Chunk {} attempt {}/{} via {} failed: {}", ch.index(), attempt,
                            settings.chunkAttempts(), proxy, e.toString());
                    pool.penalize(proxy, false);
                    listener.onChunkRetry(url, ch.index(), attempt, proxy, String.valueOf(e.getMessage()));
                    sleep(exponentialBackoff(attempt));
                }
            }
            throw new DownloadException("chunk " + ch.index() + " failed after " + settings.chunkAttempts()
                    + " attempts: " + (last == null ? "?" : last.getMessage()));
        }

        private void markDone(Chunk ch) {
            List<Chunk> snapshot;
            synchronized (done) {
                done.add(ch);
                snapshot = new ArrayList<>(done);
            }
            synchronized (this) {
                ProgressSidecar.save(part, file.handle(), file.size(), settings.chunkSize(), snapshot);
            }
        }

        /** Re-resolves the CDN URL once for all workers that saw the same expired URL. */
        private void refreshCdnUrl(String expired) {
            synchronized (cdnLock) {
                if (!expired.equals(cdnUrl)) {
                    return; // someone else already refreshed it
                }
                try {
                    cdnUrl = resolve(url, this::shouldStop).cdnUrl();
                    log.info("{}: CDN URL refreshed", file.fileName());
                } catch (RuntimeException e) {
                    log.warn("{}: CDN URL refresh failed: {}", file.fileName(), e.getMessage());
                }
            }
        }

        private void checkStop() {
            if (control.isCancelled()) {
                throw new StopException("cancelled");
            }
            if (abort.get()) {
                throw new StopException("aborted");
            }
        }

        private void sleep(double seconds) {
            if (Threads.sleepInterruptibly(seconds, this::shouldStop)) {
                throw new StopException("stopped during back-off");
            }
        }

        private double exponentialBackoff(int attempt) {
            return Math.min(settings.backoffMax().toMillis() / 1000.0, 1 << Math.min(attempt, 6));
        }

        private double retryAfter(Response resp, double fallback) {
            Double seconds = parseRetryAfter(resp.header("Retry-After"));
            double cap = settings.retryAfterCap().toMillis() / 1000.0;
            return seconds == null ? fallback : Math.min(cap, Math.max(0, seconds));
        }
    }

    /** Seconds from a Retry-After header ("N" or an HTTP date), or {@code null}. */
    static Double parseRetryAfter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.strip();
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException ignored) {
            // not a number: try an HTTP date
        }
        try {
            ZonedDateTime when = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME);
            return Math.max(0, Duration.between(ZonedDateTime.now(when.getZone()), when).toMillis() / 1000.0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1;
        }
    }

    private static void moveIntoPlace(Path source, Path target) {
        try {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new DownloadException("Cannot rename " + source + " to " + target, e);
        }
    }
}
