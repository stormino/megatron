package io.github.stormino.megatron.proxy;

import io.github.stormino.megatron.util.Threads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Background thread that keeps the pool healthy without stopping downloads.
 *
 * <p>Hysteresis: while <i>armed</i>, a refill fires when alive proxies drop below {@code low};
 * after a refill it disarms until the pool climbs back to {@code high}. A refill also fires if
 * none happened for {@code maxInterval}, and never two within {@code minInterval}.
 */
public final class PoolRefresher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PoolRefresher.class);

    /**
     * Refresher tunables.
     *
     * <p>Defaults fit real free-proxy pools, which hold tens of usable proxies (a refill validates
     * thousands of candidates, so it is expensive): refill when fewer than 8 are alive, never more
     * than every 10 minutes, and otherwise every 6 hours to replace proxies that went stale.
     */
    public record Settings(Duration interval, int low, int high, Duration minInterval, Duration maxInterval) {
        public static Settings defaults() {
            return new Settings(Duration.ofMinutes(1), 8, 20, Duration.ofMinutes(10), Duration.ofHours(6));
        }
    }

    private final ProxyPool pool;
    private final Settings settings;
    private final LongSupplier clock;
    private ScheduledExecutorService executor;
    private long lastRefillNanos;
    private boolean armed = true;

    public PoolRefresher(ProxyPool pool, Settings settings) {
        this(pool, settings, System::nanoTime);
    }

    PoolRefresher(ProxyPool pool, Settings settings, LongSupplier clock) {
        this.pool = pool;
        this.settings = settings;
        this.clock = clock;
        this.lastRefillNanos = clock.getAsLong();
    }

    public synchronized void start() {
        if (executor != null) {
            return;
        }
        lastRefillNanos = clock.getAsLong();
        armed = true;
        executor = Executors.newSingleThreadScheduledExecutor(Threads.daemonFactory("megatron-refresher"));
        long period = settings.interval().toMillis();
        executor.scheduleWithFixedDelay(this::safeTick, period, period, TimeUnit.MILLISECONDS);
        log.info("Pool refresher started ({})", settings);
    }

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException e) {
            log.warn("Pool refresher tick failed", e);
        }
    }

    /** One check, plus a refill if needed. Package-private so tests can drive the hysteresis. */
    synchronized boolean tick() {
        int alive = pool.size();
        Duration since = Duration.ofNanos(clock.getAsLong() - lastRefillNanos);
        if (!armed && alive >= settings.high()) {
            armed = true;
        }
        boolean belowThreshold = armed && alive < settings.low();
        boolean timedOut = since.compareTo(settings.maxInterval()) > 0;
        boolean pastMinInterval = since.compareTo(settings.minInterval()) >= 0;
        if (!(belowThreshold || timedOut) || !pastMinInterval) {
            return false;
        }
        log.info("Pool refresher: refilling (alive={}, belowThreshold={}, idleFor={}s)", alive, belowThreshold,
                since.toSeconds());
        int added = pool.refillBlocking(true);
        lastRefillNanos = clock.getAsLong();
        armed = false;
        log.info("Pool refresher: +{} proxies", added);
        return true;
    }

    @Override
    public synchronized void close() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
