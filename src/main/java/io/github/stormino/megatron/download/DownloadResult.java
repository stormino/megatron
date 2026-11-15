package io.github.stormino.megatron.download;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Outcome of a successful download.
 *
 * @param resumedBytes bytes already on disk from a previous run
 * @param skipped      true if the file already existed with the right size and nothing was fetched
 */
public record DownloadResult(String url, String handle, Path path, long size, long resumedBytes,
                             Duration elapsed, boolean skipped) {

    /** Average speed of this run in bytes per second (resumed bytes excluded). */
    public double averageBytesPerSecond() {
        double secs = elapsed.toMillis() / 1000.0;
        return secs <= 0 ? 0 : (size - resumedBytes) / secs;
    }
}
