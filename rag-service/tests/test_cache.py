import threading
import time
import unittest

from app.cache import SingleFlightTTLCache


class SingleFlightTTLCacheTest(unittest.TestCase):
    def test_concurrent_requests_share_one_loader(self):
        cache = SingleFlightTTLCache(ttl_seconds=1, max_entries=8)
        barrier = threading.Barrier(6)
        release = threading.Event()
        load_count = 0
        load_lock = threading.Lock()
        values = []

        def loader():
            nonlocal load_count
            with load_lock:
                load_count += 1
            release.wait(timeout=2)
            return "value"

        def worker():
            barrier.wait(timeout=2)
            values.append(cache.get_or_load("same", loader))

        threads = [threading.Thread(target=worker) for _ in range(6)]
        for thread in threads:
            thread.start()
        time.sleep(0.05)
        release.set()
        for thread in threads:
            thread.join(timeout=2)

        self.assertEqual(["value"] * 6, values)
        self.assertEqual(1, load_count)
        self.assertEqual(5, cache.stats()["coalescedRequests"])
        self.assertEqual("value", cache.get_or_load("same", lambda: "unexpected"))
        self.assertEqual(1, cache.stats()["hits"])


if __name__ == "__main__":
    unittest.main()
