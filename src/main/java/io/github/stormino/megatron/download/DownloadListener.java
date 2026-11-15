package io.github.stormino.megatron.download;

import io.github.stormino.megatron.proxy.ProxyInfo;

import java.nio.file.Path;

/**
 * Download callbacks. Every method has an empty default; they may be called from worker
 * threads, so implementations must be thread-safe and fast.
 */
public interface DownloadListener {

    DownloadListener NONE = new DownloadListener() {
    };

    /** The link was resolved: name, size and final destination are known. */
    default void onResolved(String url, String fileName, long size, Path target) {
    }

    /** Periodic progress (about once per second) and once at the end. */
    default void onProgress(String url, long bytesDone, long totalBytes, double bytesPerSecond) {
    }

    /** A chunk attempt is starting through {@code proxy}. */
    default void onChunkAttempt(String url, int chunkIndex, int attempt, ProxyInfo proxy) {
    }

    /** A chunk was downloaded, decrypted and written. */
    default void onChunkCompleted(String url, int chunkIndex, long bytes, ProxyInfo proxy, long millis) {
    }

    /** A chunk attempt failed and will be retried (if attempts remain). */
    default void onChunkRetry(String url, int chunkIndex, int attempt, ProxyInfo proxy, String reason) {
    }

    /** The whole file finished. */
    default void onCompleted(DownloadResult result) {
    }

    /** The file failed (after file-level retries, if any). */
    default void onFailed(String url, Throwable error) {
    }
}
