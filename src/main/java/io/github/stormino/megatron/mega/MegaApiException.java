package io.github.stormino.megatron.mega;

import io.github.stormino.megatron.MegatronException;

/**
 * Error returned by (or while talking to) the Mega public API.
 *
 * <p>{@link #apiCode()} carries Mega's numeric error code (e.g. {@code -9} = not found,
 * {@code -11} = access denied) when the API answered with one, otherwise {@code 0}.
 */
public class MegaApiException extends MegatronException {

    private final int apiCode;

    public MegaApiException(String message) {
        this(message, 0, null);
    }

    public MegaApiException(String message, int apiCode) {
        this(message, apiCode, null);
    }

    public MegaApiException(String message, int apiCode, Throwable cause) {
        super(message, cause);
        this.apiCode = apiCode;
    }

    public int apiCode() {
        return apiCode;
    }
}
