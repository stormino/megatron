package io.github.stormino.megatron;

import io.github.stormino.megatron.download.DownloadResult;

import java.util.List;
import java.util.Map;

/**
 * Outcome of {@link Megatron#downloadAll}.
 *
 * @param completed files downloaded (or already present)
 * @param failed    URL (file link or folder job URL) to the error that stopped it
 */
public record BatchResult(List<DownloadResult> completed, Map<String, Throwable> failed) {

    public BatchResult {
        completed = List.copyOf(completed);
        failed = Map.copyOf(failed);
    }

    public boolean allSucceeded() {
        return failed.isEmpty();
    }
}
