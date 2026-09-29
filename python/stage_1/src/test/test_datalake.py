"""
Unit tests for datalake.range_based
===================================
Verifies the mathematical logic for range-based folder assignment
without writing to disk.
"""

import unittest

from ..main.bigdata.datalake.range_based import _get_range_paths


class TestGetRangePaths(unittest.TestCase):
    """Test suite for the _get_range_paths function of the range_based module."""

    def test_book_1530_goes_to_01000_01999(self):
        """Book with ID 1530 must be stored in the 01000-01999 folder."""
        header_path, body_path = _get_range_paths(1530)
        self.assertIn("01000-01999", header_path)
        self.assertIn("01000-01999", body_path)

    def test_book_999_goes_to_00000_00999(self):
        """Book with ID 999 must be stored in the 00000-00999 folder."""
        header_path, body_path = _get_range_paths(999)
        self.assertIn("00000-00999", header_path)
        self.assertIn("00000-00999", body_path)

    def test_book_0_goes_to_00000_00999(self):
        """Book with ID 0 must go to the 00000-00999 folder."""
        header_path, body_path = _get_range_paths(0)
        self.assertIn("00000-00999", header_path)

    def test_book_2000_goes_to_02000_02999(self):
        """Book with ID 2000 (range boundary) → 02000-02999."""
        header_path, body_path = _get_range_paths(2000)
        self.assertIn("02000-02999", header_path)
        self.assertIn("02000-02999", body_path)

    def test_paths_contain_book_id(self):
        """The returned paths must include the book ID in the filename."""
        header_path, body_path = _get_range_paths(1530)
        self.assertIn("1530.header.txt", header_path)
        self.assertIn("1530.body.txt", body_path)


if __name__ == "__main__":
    unittest.main()
