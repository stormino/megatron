package io.github.stormino.megatron.download;

/** The download was cancelled through {@link DownloadControl#cancel()}. */
public class DownloadCancelledException extends DownloadException {

    public DownloadCancelledException(String message) {
        super(message);
    }
}
