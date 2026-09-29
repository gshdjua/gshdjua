package com.example.demo.service.cache;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Small in-process TTL cache that lets only one caller load a missing key.
 * Other callers wait for that same future instead of repeating database or HTTP work.
 */
public final class TimedSingleFlightCache<K, V> {
    private final ConcurrentHashMap<K, Entry<V>> values = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong coalesced = new AtomicLong();
    private final AtomicLong loads = new AtomicLong();
    private final AtomicLong loadNanos = new AtomicLong();
    private volatile long ttlMillis;
    private volatile int maxEntries;

    public TimedSingleFlightCache(long ttlMillis, int maxEntries) {
        configure(ttlMillis, maxEntries);
    }

    public void configure(long ttlMillis, int maxEntries) {
        this.ttlMillis = Math.max(1L, ttlMillis);
        this.maxEntries = Math.max(1, maxEntries);
        trim(System.currentTimeMillis());
    }

    public V get(K key, Supplier<V> loader) {
        long now = System.currentTimeMillis();
        Entry<V> cached = values.get(key);
        if (cached != null && cached.expiresAt > now) {
            hits.incrementAndGet();
            return cached.value;
        }
        if (cached != null) values.remove(key, cached);

        CompletableFuture<V> promise = new CompletableFuture<>();
        CompletableFuture<V> existing = inFlight.putIfAbsent(key, promise);
        if (existing != null) {
            coalesced.incrementAndGet();
            return await(existing);
        }

        misses.incrementAndGet();
        long started = System.nanoTime();
        try {
            V value = loader.get();
            if (value != null) {
                trim(System.currentTimeMillis());
                values.put(key, new Entry<>(value, System.currentTimeMillis() + ttlMillis));
            }
            loads.incrementAndGet();
            loadNanos.addAndGet(System.nanoTime() - started);
            promise.complete(value);
            return value;
        } catch (Throwable error) {
            promise.completeExceptionally(error);
            if (error instanceof RuntimeException) throw (RuntimeException) error;
            throw new IllegalStateException(error);
        } finally {
            inFlight.remove(key, promise);
        }
    }

    public void clear() {
        values.clear();
    }

    public Map<String, Object> stats() {
        long hitCount = hits.get();
        long missCount = misses.get();
        long loadCount = loads.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entries", values.size());
        result.put("inFlight", inFlight.size());
        result.put("hits", hitCount);
        result.put("misses", missCount);
        result.put("coalescedRequests", coalesced.get());
        result.put("hitRate", hitCount + missCount == 0 ? 0D : round((double) hitCount / (hitCount + missCount)));
        result.put("averageLoadMs", loadCount == 0 ? 0D : round(loadNanos.get() / 1_000_000D / loadCount));
        result.put("ttlMs", ttlMillis);
        result.put("maxEntries", maxEntries);
        return result;
    }

    private V await(CompletableFuture<V> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw exception;
        }
    }

    private void trim(long now) {
        for (Map.Entry<K, Entry<V>> item : values.entrySet()) {
            if (item.getValue().expiresAt <= now) values.remove(item.getKey(), item.getValue());
        }
        int overflow = values.size() - maxEntries + 1;
        if (overflow <= 0) return;
        Iterator<K> keys = values.keySet().iterator();
        while (overflow-- > 0 && keys.hasNext()) values.remove(keys.next());
    }

    private double round(double value) {
        return Math.round(value * 10000D) / 10000D;
    }

    private static final class Entry<V> {
        private final V value;
        private final long expiresAt;

        private Entry(V value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }
}
