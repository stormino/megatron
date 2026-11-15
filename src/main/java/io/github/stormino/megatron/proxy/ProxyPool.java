package io.github.stormino.megatron.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Thread-safe rotating pool of proxies with a reputation score.
 *
 * <ul>
 *   <li>Successes raise the score, failures lower it; at or below the dead threshold a proxy is
 *       excluded from rotation (it can come back if a refill finds it again).</li>
 *   <li>{@link SelectionMode#SCORE}: round-robin over the top-score tier, latency as tiebreaker.</li>
 *   <li>{@link SelectionMode#THROUGHPUT}: round-robin over the top-K proxies by observed throughput
 *       (EMA), with one slot reserved to explore unmeasured proxies.</li>
 *   <li>{@link #cooldown} benches a proxy for a while (CDN rate-limit) without touching its score;
 *       benched proxies do not count as alive, so an all-benched pool still triggers refills.</li>
 *   <li>{@link #refillBlocking} serialises refills: the first caller scrapes and validates, the
 *       others wait and then find the pool already populated.</li>
 * </ul>
 */
public final class ProxyPool {

    private static final Logger log = LoggerFactory.getLogger(ProxyPool.class);

    public enum SelectionMode { SCORE, THROUGHPUT }

    /** Scoring parameters. */
    public record Settings(int initialScore, int successDelta, int failureDelta, int deadThreshold, int maxScore,
                           double cacheDecay, Duration cooldown, boolean latencyTiebreaker,
                           double throughputEmaAlpha, int throughputTopKFactor) {
        public static Settings defaults() {
            return new Settings(0, 5, -10, -20, 100, 0.5, Duration.ofSeconds(90), true, 0.3, 2);
        }
    }

    private final Settings settings;
    private final LongSupplier clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final ReentrantLock refillLock = new ReentrantLock();

    private final Map<String, ProxyInfo> proxies = new LinkedHashMap<>();
    private final Map<String, Integer> score = new HashMap<>();
    private final Map<String, Integer> latency = new HashMap<>();
    private final Map<String, Double> throughput = new HashMap<>();
    private final Map<String, Long> cooldownUntil = new HashMap<>();
    private int index;

    private volatile Supplier<List<ProxyInfo>> refillFunction;
    private volatile SelectionMode selectionMode = SelectionMode.SCORE;
    private volatile int connections = 10;

    private int discarded;
    private int refills;
    private long lastRefillNanos = -1;

    public ProxyPool() {
        this(null, Settings.defaults(), System::nanoTime);
    }

    public ProxyPool(Supplier<List<ProxyInfo>> refillFunction) {
        this(refillFunction, Settings.defaults(), System::nanoTime);
    }

    /**
     * @param clock monotonic nanosecond clock (injectable for tests)
     */
    public ProxyPool(Supplier<List<ProxyInfo>> refillFunction, Settings settings, LongSupplier clock) {
        this.refillFunction = refillFunction;
        this.settings = settings;
        this.clock = clock;
    }

    public Settings settings() {
        return settings;
    }

    public void refillFunction(Supplier<List<ProxyInfo>> refillFunction) {
        this.refillFunction = refillFunction;
    }

    public SelectionMode selectionMode() {
        return selectionMode;
    }

    public void selectionMode(SelectionMode mode) {
        this.selectionMode = mode;
    }

    /** Connections per file: sizes the top-K subset of {@link SelectionMode#THROUGHPUT}. */
    public void connections(int connections) {
        this.connections = Math.max(1, connections);
    }

    // ---------------------------------------------------------------- admission

    public void addAll(Collection<ProxyInfo> fresh) {
        int alive;
        lock.lock();
        try {
            for (ProxyInfo p : fresh) {
                admitLocked(p);
            }
            alive = countAliveLocked();
        } finally {
            lock.unlock();
        }
        log.info("Pool: added {} proxies (alive: {})", fresh.size(), alive);
    }

    private void admitLocked(ProxyInfo p) {
        String key = p.key();
        Integer cur = score.get(key);
        if (cur == null || cur < settings.deadThreshold()) {
            score.put(key, seedScore(p));
        }
        if (p.latencyMs() != null && p.latencyMs() > 0) {
            latency.put(key, p.latencyMs());
        }
        if (p.throughputBps() != null && p.throughputBps() > 0) {
            throughput.put(key, p.throughputBps());
        }
        proxies.put(key, p);
    }

    /**
     * Fresh proxies start neutral. A score from the cache is trusted only if it was good, and
     * decayed halfway back to neutral: free proxies change quality hour by hour.
     */
    int seedScore(ProxyInfo p) {
        Integer cached = p.cachedScore();
        int initial = settings.initialScore();
        if (cached == null || cached <= initial) {
            return initial;
        }
        double decayed = initial + (cached - initial) * settings.cacheDecay();
        return Math.max(initial, Math.min(settings.maxScore(), (int) Math.round(decayed)));
    }

    // ---------------------------------------------------------------- selection

    /** Next proxy to use, or {@code null} if none is currently eligible. */
    public ProxyInfo next() {
        lock.lock();
        try {
            List<ProxyInfo> eligible = eligibleLocked();
            if (eligible.isEmpty()) {
                return null;
            }
            List<ProxyInfo> subset = selectionMode == SelectionMode.THROUGHPUT
                    ? throughputTopKLocked(eligible)
                    : topScoreTierLocked(eligible);
            int n = subset.size();
            ProxyInfo chosen = subset.get(Math.floorMod(index, n));
            index = (index + 1) % n;
            return chosen;
        } finally {
            lock.unlock();
        }
    }

    private List<ProxyInfo> eligibleLocked() {
        long now = clock.getAsLong();
        List<ProxyInfo> out = new ArrayList<>();
        for (ProxyInfo p : proxies.values()) {
            if (isAliveLocked(p.key(), now)) {
                out.add(p);
            }
        }
        return out;
    }

    private boolean isAliveLocked(String key, long now) {
        if (score.getOrDefault(key, settings.initialScore()) <= settings.deadThreshold()) {
            return false;
        }
        Long until = cooldownUntil.get(key);
        return until == null || until - now <= 0;
    }

    private List<ProxyInfo> topScoreTierLocked(List<ProxyInfo> eligible) {
        int top = Integer.MIN_VALUE;
        for (ProxyInfo p : eligible) {
            top = Math.max(top, score.getOrDefault(p.key(), settings.initialScore()));
        }
        List<ProxyInfo> tier = new ArrayList<>();
        for (ProxyInfo p : eligible) {
            if (score.getOrDefault(p.key(), settings.initialScore()) == top) {
                tier.add(p);
            }
        }
        if (settings.latencyTiebreaker() && tier.size() > 1) {
            tier.sort(Comparator.comparing((ProxyInfo p) -> latency.get(p.key()),
                    Comparator.nullsLast(Comparator.naturalOrder())));
        }
        return tier;
    }

    private List<ProxyInfo> throughputTopKLocked(List<ProxyInfo> eligible) {
        List<ProxyInfo> measured = new ArrayList<>();
        List<ProxyInfo> unmeasured = new ArrayList<>();
        for (ProxyInfo p : eligible) {
            (throughput.containsKey(p.key()) ? measured : unmeasured).add(p);
        }
        measured.sort(Comparator.comparingDouble((ProxyInfo p) -> throughput.get(p.key())).reversed());
        int k = Math.max(1, connections * settings.throughputTopKFactor());
        List<ProxyInfo> top = new ArrayList<>(measured.subList(0, Math.min(k, measured.size())));
        if (!unmeasured.isEmpty()) {
            if (top.size() >= k) {
                top = new ArrayList<>(top.subList(0, Math.max(0, k - 1)));
            }
            int explore = Math.max(1, k - top.size());
            top.addAll(unmeasured.subList(0, Math.min(explore, unmeasured.size())));
        }
        return top.isEmpty() ? eligible : top;
    }

    // ---------------------------------------------------------------- feedback

    public void recordSuccess(ProxyInfo proxy) {
        lock.lock();
        try {
            int cur = score.getOrDefault(proxy.key(), settings.initialScore());
            score.put(proxy.key(), Math.min(settings.maxScore(), cur + settings.successDelta()));
        } finally {
            lock.unlock();
        }
    }

    public void recordFailure(ProxyInfo proxy) {
        lock.lock();
        try {
            int cur = score.getOrDefault(proxy.key(), settings.initialScore());
            int updated = cur + settings.failureDelta();
            score.put(proxy.key(), updated);
            if (cur > settings.deadThreshold() && updated <= settings.deadThreshold()) {
                discarded++;
            }
        } finally {
            lock.unlock();
        }
    }

    /** Observed throughput (bytes/s), folded into an EMA used by {@link SelectionMode#THROUGHPUT}. */
    public void recordThroughput(ProxyInfo proxy, double bytesPerSecond) {
        if (bytesPerSecond <= 0) {
            return;
        }
        lock.lock();
        try {
            Double prev = throughput.get(proxy.key());
            double alpha = settings.throughputEmaAlpha();
            throughput.put(proxy.key(), prev == null ? bytesPerSecond : alpha * bytesPerSecond + (1 - alpha) * prev);
        } finally {
            lock.unlock();
        }
    }

    /** Soft penalty = {@link #recordFailure}; hard penalty drops the proxy below the dead threshold at once. */
    public void penalize(ProxyInfo proxy, boolean hard) {
        if (!hard) {
            recordFailure(proxy);
            return;
        }
        lock.lock();
        try {
            int cur = score.getOrDefault(proxy.key(), settings.initialScore());
            if (cur > settings.deadThreshold()) {
                discarded++;
            }
            score.put(proxy.key(), settings.deadThreshold() - 1);
        } finally {
            lock.unlock();
        }
        log.debug("Pool: hard penalty for {}", proxy);
    }

    public void cooldown(ProxyInfo proxy) {
        cooldown(proxy, settings.cooldown());
    }

    /** Benches the proxy for {@code duration} without touching its score. */
    public void cooldown(ProxyInfo proxy, Duration duration) {
        lock.lock();
        try {
            cooldownUntil.put(proxy.key(), clock.getAsLong() + duration.toNanos());
        } finally {
            lock.unlock();
        }
        log.debug("Pool: cooldown {} for {}s", proxy, duration.toSeconds());
    }

    // ---------------------------------------------------------------- stats

    /** Number of proxies currently selectable (alive and not in cooldown). */
    public int size() {
        lock.lock();
        try {
            return countAliveLocked();
        } finally {
            lock.unlock();
        }
    }

    private int countAliveLocked() {
        long now = clock.getAsLong();
        int n = 0;
        for (String key : proxies.keySet()) {
            if (isAliveLocked(key, now)) {
                n++;
            }
        }
        return n;
    }

    public int cooldownCount() {
        lock.lock();
        try {
            long now = clock.getAsLong();
            return (int) cooldownUntil.values().stream().filter(u -> u - now > 0).count();
        } finally {
            lock.unlock();
        }
    }

    public int scoreOf(ProxyInfo proxy) {
        lock.lock();
        try {
            return score.getOrDefault(proxy.key(), settings.initialScore());
        } finally {
            lock.unlock();
        }
    }

    /** Proxies that went from alive to dead during this session. */
    public int discardedCount() {
        lock.lock();
        try {
            return discarded;
        } finally {
            lock.unlock();
        }
    }

    public int refillCount() {
        lock.lock();
        try {
            return refills;
        } finally {
            lock.unlock();
        }
    }

    /** Time since the last completed refill, or {@code null} if none happened. */
    public Duration sinceLastRefill() {
        lock.lock();
        try {
            return lastRefillNanos < 0 ? null : Duration.ofNanos(clock.getAsLong() - lastRefillNanos);
        } finally {
            lock.unlock();
        }
    }

    /** Snapshot of non-dead proxies with score {@code >= minScore}, with score and latency, for the cache. */
    public List<ProxyInfo> exportForCache(int minScore) {
        lock.lock();
        try {
            List<ProxyInfo> out = new ArrayList<>();
            for (ProxyInfo p : proxies.values()) {
                int s = score.getOrDefault(p.key(), settings.initialScore());
                if (s < minScore || s < settings.deadThreshold()) {
                    continue;
                }
                ProxyInfo copy = new ProxyInfo(p.host(), p.port(), p.protocol(), p.username(), p.password(), "cache");
                copy.cachedScore(s);
                copy.latencyMs(latency.get(p.key()));
                out.add(copy);
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- refill

    /**
     * Runs the refill function and admits what it returns. Without {@code force}, it is skipped
     * if alive proxies are already available (typically because another thread just refilled).
     *
     * @return number of proxies admitted
     */
    public int refillBlocking(boolean force) {
        Supplier<List<ProxyInfo>> fn = refillFunction;
        if (fn == null) {
            return 0;
        }
        refillLock.lock();
        try {
            if (!force && size() > 0) {
                return 0;
            }
            log.info("Pool: refilling (force={})...", force);
            List<ProxyInfo> fresh;
            try {
                fresh = fn.get();
            } catch (RuntimeException e) {
                log.warn("Pool: refill failed", e);
                return 0;
            }
            int alive;
            lock.lock();
            try {
                for (ProxyInfo p : fresh) {
                    admitLocked(p);
                }
                alive = countAliveLocked();
                refills++;
                lastRefillNanos = clock.getAsLong();
            } finally {
                lock.unlock();
            }
            log.info("Pool: refill done, {} admitted (alive: {})", fresh.size(), alive);
            return fresh.size();
        } finally {
            refillLock.unlock();
        }
    }
}
