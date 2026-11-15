package io.github.stormino.megatron.download;

import io.github.stormino.megatron.MegatronException;

/**
 * A download did not complete. Partial data and the progress sidecar are left on disk, so
 * downloading the same URL into the same directory resumes where it stopped.
 */
public class DownloadException extends MegatronException {

    public DownloadException(String message) {
        super(message);
    }

    public DownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
