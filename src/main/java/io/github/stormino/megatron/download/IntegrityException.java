package io.github.stormino.megatron.download;

/**
 * The downloaded file does not match Mega's meta-MAC. The partial data has already been
 * discarded, so a retry downloads the file again from scratch.
 */
public class IntegrityException extends DownloadException {

    public IntegrityException(String message) {
        super(message);
    }
}
