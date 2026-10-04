"""
Unit tests for datalake.range_based
===================================
Verifies the mathematical logic for range-based folder assignment
without writing to disk.
"""

import unittest

from ...main.bigdata.datalake.range_based import RangeBasedDatalake


class TestRangeBasedDatalake(unittest.TestCase):
    """Test suite for the RangeBasedDatalake class."""

    def setUp(self):
        self.datalake = RangeBasedDatalake("/dummy/root")

    def test_book_1530_goes_to_01000_01999(self):
        """Book with ID 1530 must be stored in the 01000-01999 folder."""
        range_dir = str(self.datalake._get_range_dir(1530))
        self.assertIn("01000-01999", range_dir)

    def test_book_999_goes_to_00000_00999(self):
        """Book with ID 999 must be stored in the 00000-00999 folder."""
        range_dir = str(self.datalake._get_range_dir(999))
        self.assertIn("00000-00999", range_dir)

    def test_book_0_goes_to_00000_00999(self):
        """Book with ID 0 must go to the 00000-00999 folder."""
        range_dir = str(self.datalake._get_range_dir(0))
        self.assertIn("00000-00999", range_dir)

    def test_book_2000_goes_to_02000_02999(self):
        """Book with ID 2000 (range boundary) -> 02000-02999."""
        range_dir = str(self.datalake._get_range_dir(2000))
        self.assertIn("02000-02999", range_dir)


if __name__ == "__main__":
    unittest.main()
