"""
Unit tests for the book and time datalakes (SPEC section 3).

The time datalake uses the benchmark's simulated clock: 10 books per hour from
1 January 2026 00:00 UTC, so its folders are known in advance.
"""

import tempfile
import unittest
from pathlib import Path

from ...main.bigdata.benchmark.datalake_benchmark import SimulatedClock
from ...main.bigdata.datalake.book_based import BookBasedDatalake
from ...main.bigdata.datalake.time_based import TimeBasedDatalake
from ...main.bigdata.models import RawBook


def _book(book_id: int) -> RawBook:
    return RawBook(id=book_id, header=f"Title: Book {book_id}", body=f"body of book {book_id}\nsecond line")


class TestBookBasedDatalake(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.datalake = BookBasedDatalake(self.root)

    def tearDown(self):
        self._tmp.cleanup()

    def test_save_uses_the_spec_paths_and_keeps_the_text(self):
        location = self.datalake.save(_book(1342))
        self.assertEqual(location.header_path, self.root / "1342" / "header.txt")
        self.assertEqual(location.body_path, self.root / "1342" / "body.txt")
        self.assertEqual(location.body_path.read_bytes(), b"body of book 1342\nsecond line")
        self.assertEqual(list(self.root.rglob("*.tmp")), [])

    def test_locate_and_list(self):
        for book_id in (84, 11, 1342):
            self.datalake.save(_book(book_id))
        self.assertEqual(self.datalake.list_book_ids(), [11, 84, 1342])
        self.assertIsNotNone(self.datalake.locate(84))
        self.assertIsNone(self.datalake.locate(5))

    def test_a_body_left_as_tmp_is_not_a_saved_book(self):
        location = self.datalake.save(_book(84))
        location.body_path.rename(location.body_path.with_name("body.txt.tmp"))
        self.assertIsNone(self.datalake.locate(84))
        self.assertEqual(self.datalake.list_book_ids(), [])


class TestTimeBasedDatalake(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.datalake = TimeBasedDatalake(self.root, clock=SimulatedClock())

    def tearDown(self):
        self._tmp.cleanup()

    def test_ten_books_per_hour_with_the_simulated_clock(self):
        for book_id in range(1, 13):
            self.datalake.save(_book(book_id))
        first_hour = self.root / "20260101" / "00"
        second_hour = self.root / "20260101" / "01"
        self.assertEqual(len(list(first_hour.glob("*.body.txt"))), 10)
        self.assertEqual(sorted(p.name for p in second_hour.glob("*.body.txt")),
                         ["11.body.txt", "12.body.txt"])
        self.assertEqual(self.datalake.list_book_ids(), list(range(1, 13)))

    def test_locate_returns_the_newest_copy_and_list_reports_it_once(self):
        self.datalake.save(_book(84))
        for book_id in range(100, 110):          # fill the first hour
            self.datalake.save(_book(book_id))
        self.datalake.save(_book(84))            # same book again, one hour later
        location = self.datalake.locate(84)
        self.assertEqual(location.body_path.parent, self.root / "20260101" / "01")
        self.assertEqual(self.datalake.list_book_ids().count(84), 1)


if __name__ == "__main__":
    unittest.main()
