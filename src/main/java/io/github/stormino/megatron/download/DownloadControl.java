package io.github.stormino.megatron.download;

/**
 * Cooperative pause / cancel switch. One instance can be shared across many downloads to pause or
 * cancel them all at once. Cancellation is final; checks happen between network buffers.
 */
public final class DownloadControl {

    private final Object monitor = new Object();
    private volatile boolean paused;
    private volatile boolean cancelled;

    public void pause() {
        paused = true;
    }

    public void resume() {
        synchronized (monitor) {
            paused = false;
            monitor.notifyAll();
        }
    }

    public void cancel() {
        synchronized (monitor) {
            cancelled = true;
            monitor.notifyAll();
        }
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** Blocks while paused; returns immediately if cancelled. */
    public void awaitIfPaused() {
        synchronized (monitor) {
            while (paused && !cancelled) {
                try {
                    monitor.wait(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
