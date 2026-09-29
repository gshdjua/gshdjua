"""Thread-safe bounded TTL cache with request coalescing and lightweight metrics."""

import threading
import time
from collections import OrderedDict
from concurrent.futures import Future
from typing import Callable, Dict, Generic, Hashable, TypeVar


K = TypeVar("K", bound=Hashable)
V = TypeVar("V")


class SingleFlightTTLCache(Generic[K, V]):
    def __init__(self, ttl_seconds: float, max_entries: int) -> None:
        self._ttl = max(float(ttl_seconds), 0.001)
        self._max_entries = max(int(max_entries), 1)
        self._values: "OrderedDict[K, tuple[float, V]]" = OrderedDict()
        self._inflight: Dict[K, Future[V]] = {}
        self._lock = threading.Lock()
        self._hits = 0
        self._misses = 0
        self._coalesced = 0
        self._loads = 0
        self._load_seconds = 0.0

    def get_or_load(self, key: K, loader: Callable[[], V]) -> V:
        now = time.monotonic()
        with self._lock:
            cached = self._values.get(key)
            if cached is not None and cached[0] > now:
                self._hits += 1
                self._values.move_to_end(key)
                return cached[1]
            if cached is not None:
                self._values.pop(key, None)
            future = self._inflight.get(key)
            if future is not None:
                self._coalesced += 1
                leader = False
            else:
                future = Future()
                self._inflight[key] = future
                self._misses += 1
                leader = True

        if not leader:
            return future.result()

        started = time.monotonic()
        try:
            value = loader()
            with self._lock:
                self._values[key] = (time.monotonic() + self._ttl, value)
                self._values.move_to_end(key)
                while len(self._values) > self._max_entries:
                    self._values.popitem(last=False)
                self._loads += 1
                self._load_seconds += time.monotonic() - started
            future.set_result(value)
            return value
        except BaseException as error:
            future.set_exception(error)
            raise
        finally:
            with self._lock:
                self._inflight.pop(key, None)

    def clear(self) -> None:
        with self._lock:
            self._values.clear()

    def stats(self) -> dict:
        with self._lock:
            requests = self._hits + self._misses
            return {
                "entries": len(self._values),
                "inFlight": len(self._inflight),
                "hits": self._hits,
                "misses": self._misses,
                "coalescedRequests": self._coalesced,
                "hitRate": round(self._hits / requests, 4) if requests else 0.0,
                "averageLoadMs": round(self._load_seconds * 1000 / self._loads, 3) if self._loads else 0.0,
                "ttlSeconds": self._ttl,
                "maxEntries": self._max_entries,
            }
