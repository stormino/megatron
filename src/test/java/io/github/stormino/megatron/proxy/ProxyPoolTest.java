package io.github.stormino.megatron.proxy;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyPoolTest {

    private final AtomicLong now = new AtomicLong(1_000_000_000L);

    private ProxyPool pool(java.util.function.Supplier<List<ProxyInfo>> refill) {
        return new ProxyPool(refill, ProxyPool.Settings.defaults(), now::get);
    }

    private static ProxyInfo p(int n) {
        return new ProxyInfo("10.0.0." + n, 8080, ProxyProtocol.HTTP);
    }

    @Test
    void roundRobinOverTopTier() {
        ProxyPool pool = pool(null);
        pool.addAll(List.of(p(1), p(2), p(3)));
        Set<ProxyInfo> seen = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            seen.add(pool.next());
        }
        assertEquals(3, seen.size());
        pool.recordSuccess(p(2));
        for (int i = 0; i < 5; i++) {
            assertEquals(p(2), pool.next());
        }
    }

    @Test
    void failuresKillAndDeduplicate() {
        ProxyPool pool = pool(null);
        pool.addAll(List.of(p(1), p(1), p(2)));
        assertEquals(2, pool.size());
        pool.recordFailure(p(1));
        pool.recordFailure(p(1));
        assertEquals(1, pool.size());
        assertEquals(1, pool.discardedCount());
        pool.penalize(p(2), true);
        assertEquals(0, pool.size());
        assertNull(pool.next());
    }

    @Test
    void cooldownExcludesTemporarilyWithoutTouchingScore() {
        ProxyPool pool = pool(null);
        pool.addAll(List.of(p(1)));
        pool.cooldown(p(1), Duration.ofSeconds(90));
        assertEquals(0, pool.size());
        assertNull(pool.next());
        assertEquals(0, pool.scoreOf(p(1)));
        now.addAndGet(Duration.ofSeconds(91).toNanos());
        assertEquals(p(1), pool.next());
    }

    @Test
    void cachedScoreIsDecayedAndPenaltiesAreNotInherited() {
        ProxyPool pool = pool(null);
        ProxyInfo good = p(1);
        good.cachedScore(40);
        ProxyInfo bad = p(2);
        bad.cachedScore(-15);
        pool.addAll(List.of(good, bad));
        assertEquals(20, pool.scoreOf(good));
        assertEquals(0, pool.scoreOf(bad));
    }

    @Test
    void latencyBreaksTies() {
        ProxyPool pool = pool(null);
        ProxyInfo slow = p(1);
        slow.latencyMs(900);
        ProxyInfo fast = p(2);
        fast.latencyMs(50);
        pool.addAll(List.of(slow, fast));
        assertEquals(fast, pool.next());
    }

    @Test
    void refillIsSkippedWhenAliveAndRevivesDead() {
        AtomicInteger calls = new AtomicInteger();
        ProxyPool pool = pool(() -> {
            calls.incrementAndGet();
            return List.of(p(1), p(2));
        });
        assertEquals(2, pool.refillBlocking(false));
        assertEquals(0, pool.refillBlocking(false));
        assertEquals(1, calls.get());
        pool.penalize(p(1), true);
        pool.penalize(p(2), true);
        assertEquals(0, pool.size());
        pool.refillBlocking(false);
        assertEquals(2, pool.size());
        assertEquals(2, pool.refillCount());
    }

    @Test
    void throughputModePrefersFastestAndExploresUnmeasured() {
        ProxyPool pool = pool(null);
        pool.connections(1); // K = 2
        pool.selectionMode(ProxyPool.SelectionMode.THROUGHPUT);
        pool.addAll(List.of(p(1), p(2), p(3), p(4)));
        pool.recordThroughput(p(1), 10);
        pool.recordThroughput(p(2), 1000);
        pool.recordThroughput(p(3), 500);
        Set<ProxyInfo> seen = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            seen.add(pool.next());
        }
        // Best measured plus one exploration slot for the unmeasured proxy.
        assertEquals(Set.of(p(2), p(4)), seen);
    }

    @Test
    void exportForCacheSkipsDead() {
        ProxyPool pool = pool(null);
        pool.addAll(List.of(p(1), p(2)));
        pool.recordSuccess(p(1));
        pool.penalize(p(2), true);
        List<ProxyInfo> out = pool.exportForCache(0);
        assertEquals(1, out.size());
        assertEquals(5, out.get(0).cachedScore());
        assertNotEquals(null, out.get(0).source());
        assertTrue(out.get(0).equals(p(1)));
    }
}
