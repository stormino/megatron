package io.github.stormino.megatron.proxy;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoolRefresherTest {

    @Test
    void hysteresis() {
        AtomicLong now = new AtomicLong(0);
        AtomicInteger batch = new AtomicInteger();
        ProxyPool pool = new ProxyPool(() -> {
            int b = batch.incrementAndGet();
            List<ProxyInfo> out = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                out.add(new ProxyInfo("10." + b + ".0." + i, 80, ProxyProtocol.HTTP));
            }
            return out;
        }, ProxyPool.Settings.defaults(), now::get);
        PoolRefresher.Settings s = new PoolRefresher.Settings(Duration.ofSeconds(1), 5, 8,
                Duration.ofSeconds(10), Duration.ofSeconds(100));
        PoolRefresher r = new PoolRefresher(pool, s, now::get);

        assertFalse(r.tick(), "min interval not elapsed yet");
        now.addAndGet(Duration.ofSeconds(11).toNanos());
        assertTrue(r.tick(), "below low threshold");
        assertEquals(3, pool.size());
        now.addAndGet(Duration.ofSeconds(11).toNanos());
        assertFalse(r.tick(), "disarmed until the pool reaches high");
        now.addAndGet(Duration.ofSeconds(100).toNanos());
        assertTrue(r.tick(), "max interval forces a refill");
        assertEquals(6, pool.size());
    }
}
