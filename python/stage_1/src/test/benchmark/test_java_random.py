"""
Unit tests for JavaRandom and java_shuffle.

The expected values were produced by the JDK itself:
    new Random(42).nextInt(bound) and Collections.shuffle(list, new Random(42)).
The cross-language comparison of datalake_lookup and metadata_query depends on them.
"""

import unittest

from ...main.bigdata.benchmark.runner import JavaRandom, java_shuffle


class TestJavaRandom(unittest.TestCase):

    def test_next_int_matches_the_jdk(self):
        """Powers of two and other bounds take different paths in nextInt."""
        rnd = JavaRandom(42)
        bounds = [10, 16, 1000, 200, 100000, 7, 1 << 20]
        self.assertEqual([rnd.next_int(b) for b in bounds],
                         [0, 0, 248, 84, 69970, 4, 290537])

    def test_shuffle_matches_collections_shuffle(self):
        ids = list(range(1, 13))
        java_shuffle(ids, JavaRandom(42))
        self.assertEqual(ids, [1, 2, 7, 8, 4, 6, 11, 12, 10, 9, 5, 3])

    def test_same_seed_same_sequence(self):
        a, b = JavaRandom(7), JavaRandom(7)
        self.assertEqual([a.next_int(1000) for _ in range(50)], [b.next_int(1000) for _ in range(50)])


if __name__ == "__main__":
    unittest.main()
