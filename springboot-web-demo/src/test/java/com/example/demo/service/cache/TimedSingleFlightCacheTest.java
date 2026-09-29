package com.example.demo.service.cache;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimedSingleFlightCacheTest {
    @Test
    void coalescesConcurrentLoadsAndThenServesCacheHit() throws Exception {
        TimedSingleFlightCache<String, String> cache = new TimedSingleFlightCache<>(1000, 8);
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int index = 0; index < 6; index++) {
                futures.add(pool.submit(() -> cache.get("same", () -> {
                    loads.incrementAndGet();
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    return "value";
                })));
            }
            started.await();
            release.countDown();
            for (Future<String> future : futures) assertEquals("value", future.get());
            assertEquals("value", cache.get("same", () -> "unexpected"));
            assertEquals(1, loads.get());
            assertEquals(1L, cache.stats().get("misses"));
            assertEquals(5L, cache.stats().get("coalescedRequests"));
            assertEquals(1L, cache.stats().get("hits"));
        } finally {
            pool.shutdownNow();
        }
    }
}
