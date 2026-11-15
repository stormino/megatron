package io.github.stormino.megatron.download;

import java.time.Duration;

/**
 * Tunables of the parallel downloader. Defaults mirror the behaviour that works well with free
 * proxies: 32 MiB chunks, 10 connections per file, 8 attempts per chunk.
 *
 * @param connectionsPerFile    parallel Range connections per file (each through a different proxy)
 * @param chunkSize             fixed chunk size in bytes (rounded down to a multiple of 16)
 * @param chunkAttempts         attempts per chunk before it is declared failed
 * @param maxFailedChunks       failed chunks after which the whole file is aborted
 * @param connectTimeout        proxy connect timeout
 * @param readTimeout           socket read timeout
 * @param chunkMaxDuration      time budget for a single chunk attempt
 * @param minThroughputBps      minimum per-connection speed (bytes/s); slower attempts are killed
 * @param throughputGrace       time before the speed watchdog starts judging an attempt
 * @param throughputWindow      measurement window of the speed watchdog
 * @param backoffMax            cap of the exponential back-off between attempts
 * @param retryAfterCap         cap applied to a server's Retry-After header
 * @param tooManyIpsBackoff     base back-off on HTTP 429 (multiplied by the attempt number)
 * @param tooManyIpsBackoffMax  cap of the HTTP 429 back-off
 * @param resolveAttempts       proxies tried to resolve a link before giving up
 * @param resolveViaProxy       resolve links through pool proxies (true) or directly (false)
 * @param minFreeDiskBytes      free space to keep on the target disk in addition to the file
 * @param skipExisting          if the final file exists with the expected size, do nothing
 * @param progressInterval      how often {@link DownloadListener#onProgress} fires
 * @param verifyMac             check Mega's meta-MAC before the final rename; on mismatch the partial
 *                              data is discarded and {@link IntegrityException} is thrown
 */
public record DownloadSettings(int connectionsPerFile, long chunkSize, int chunkAttempts, int maxFailedChunks,
                               Duration connectTimeout, Duration readTimeout, Duration chunkMaxDuration,
                               long minThroughputBps, Duration throughputGrace, Duration throughputWindow,
                               Duration backoffMax, Duration retryAfterCap, Duration tooManyIpsBackoff,
                               Duration tooManyIpsBackoffMax, int resolveAttempts, boolean resolveViaProxy,
                               long minFreeDiskBytes, boolean skipExisting, Duration progressInterval,
                               boolean verifyMac) {

    public static final long MIN_PARALLEL_BYTES = 1024 * 1024;

    public static DownloadSettings defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.connectionsPerFile = connectionsPerFile;
        b.chunkSize = chunkSize;
        b.chunkAttempts = chunkAttempts;
        b.maxFailedChunks = maxFailedChunks;
        b.connectTimeout = connectTimeout;
        b.readTimeout = readTimeout;
        b.chunkMaxDuration = chunkMaxDuration;
        b.minThroughputBps = minThroughputBps;
        b.throughputGrace = throughputGrace;
        b.throughputWindow = throughputWindow;
        b.backoffMax = backoffMax;
        b.retryAfterCap = retryAfterCap;
        b.tooManyIpsBackoff = tooManyIpsBackoff;
        b.tooManyIpsBackoffMax = tooManyIpsBackoffMax;
        b.resolveAttempts = resolveAttempts;
        b.resolveViaProxy = resolveViaProxy;
        b.minFreeDiskBytes = minFreeDiskBytes;
        b.skipExisting = skipExisting;
        b.progressInterval = progressInterval;
        b.verifyMac = verifyMac;
        return b;
    }

    /** Builder with the default values. */
    public static final class Builder {
        private int connectionsPerFile = 10;
        private long chunkSize = 32L * 1024 * 1024;
        private int chunkAttempts = 8;
        private int maxFailedChunks = 3;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(30);
        private Duration chunkMaxDuration = Duration.ofSeconds(180);
        private long minThroughputBps = 200 * 1024;
        private Duration throughputGrace = Duration.ofSeconds(15);
        private Duration throughputWindow = Duration.ofSeconds(20);
        private Duration backoffMax = Duration.ofSeconds(8);
        private Duration retryAfterCap = Duration.ofSeconds(60);
        private Duration tooManyIpsBackoff = Duration.ofSeconds(6);
        private Duration tooManyIpsBackoffMax = Duration.ofSeconds(20);
        private int resolveAttempts = 5;
        private boolean resolveViaProxy = true;
        private long minFreeDiskBytes = 100L * 1024 * 1024;
        private boolean skipExisting = true;
        private Duration progressInterval = Duration.ofSeconds(1);
        private boolean verifyMac = true;

        public Builder connectionsPerFile(int v) { this.connectionsPerFile = Math.max(1, v); return this; }
        public Builder chunkSize(long v) { this.chunkSize = Math.max(16, v); return this; }
        public Builder chunkAttempts(int v) { this.chunkAttempts = Math.max(1, v); return this; }
        public Builder maxFailedChunks(int v) { this.maxFailedChunks = Math.max(1, v); return this; }
        public Builder connectTimeout(Duration v) { this.connectTimeout = v; return this; }
        public Builder readTimeout(Duration v) { this.readTimeout = v; return this; }
        public Builder chunkMaxDuration(Duration v) { this.chunkMaxDuration = v; return this; }
        /** 0 disables the speed watchdog. */
        public Builder minThroughputBps(long v) { this.minThroughputBps = Math.max(0, v); return this; }
        public Builder throughputGrace(Duration v) { this.throughputGrace = v; return this; }
        public Builder throughputWindow(Duration v) { this.throughputWindow = v; return this; }
        public Builder backoffMax(Duration v) { this.backoffMax = v; return this; }
        public Builder retryAfterCap(Duration v) { this.retryAfterCap = v; return this; }
        public Builder tooManyIpsBackoff(Duration v) { this.tooManyIpsBackoff = v; return this; }
        public Builder tooManyIpsBackoffMax(Duration v) { this.tooManyIpsBackoffMax = v; return this; }
        public Builder resolveAttempts(int v) { this.resolveAttempts = Math.max(1, v); return this; }
        public Builder resolveViaProxy(boolean v) { this.resolveViaProxy = v; return this; }
        public Builder minFreeDiskBytes(long v) { this.minFreeDiskBytes = Math.max(0, v); return this; }
        public Builder skipExisting(boolean v) { this.skipExisting = v; return this; }
        public Builder progressInterval(Duration v) { this.progressInterval = v; return this; }
        public Builder verifyMac(boolean v) { this.verifyMac = v; return this; }

        public DownloadSettings build() {
            return new DownloadSettings(connectionsPerFile, chunkSize, chunkAttempts, maxFailedChunks, connectTimeout,
                    readTimeout, chunkMaxDuration, minThroughputBps, throughputGrace, throughputWindow, backoffMax,
                    retryAfterCap, tooManyIpsBackoff, tooManyIpsBackoffMax, resolveAttempts, resolveViaProxy,
                    minFreeDiskBytes, skipExisting, progressInterval, verifyMac);
        }
    }
}
