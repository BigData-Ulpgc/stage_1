"""
Unit tests for the monolithic (JSON) inverted index.

Skeleton — basic import verification.
Full tests should cover add_document, postings, flush, clear and disk_usage.
"""

import unittest

from ...main.bigdata.datamart.index.monolithic import MonolithicJsonIndex


class TestMonolithicJsonIndex(unittest.TestCase):
    """Placeholder test suite for MonolithicJsonIndex."""

    def test_import(self):
        """Verify that MonolithicJsonIndex can be imported."""
        self.assertTrue(callable(MonolithicJsonIndex))


if __name__ == "__main__":
    unittest.main()
