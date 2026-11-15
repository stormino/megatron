package io.github.stormino.megatron.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Small threading helpers. */
public final class Threads {

    private Threads() {
    }

    /** Factory of named daemon threads. */
    public static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * Sleeps in small steps, returning early with {@code true} as soon as {@code abort} reports
     * true (or the thread is interrupted). Returns {@code false} after a full sleep.
     */
    public static boolean sleepInterruptibly(double seconds, BooleanSupplier abort) {
        long deadline = System.nanoTime() + (long) (seconds * 1_000_000_000L);
        while (true) {
            if (abort != null && abort.getAsBoolean()) {
                return true;
            }
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) {
                return false;
            }
            try {
                Thread.sleep(Math.min(250, remainingMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
    }
}
