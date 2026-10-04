"""
Unit tests for the hierarchical (folder-based) inverted index.

Skeleton — basic import verification.
Full tests should cover add_document, postings, flush, clear and disk_usage.
"""

import unittest

from ...main.bigdata.datamart.index.hierarchical import HierarchicalFolderIndex


class TestHierarchicalFolderIndex(unittest.TestCase):
    """Placeholder test suite for HierarchicalFolderIndex."""

    def test_import(self):
        """Verify that HierarchicalFolderIndex can be imported."""
        self.assertTrue(callable(HierarchicalFolderIndex))


if __name__ == "__main__":
    unittest.main()
