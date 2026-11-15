package io.github.stormino.megatron;

/** Base unchecked exception for every error raised by the library. */
public class MegatronException extends RuntimeException {

    public MegatronException(String message) {
        super(message);
    }

    public MegatronException(String message, Throwable cause) {
        super(message, cause);
    }
}
