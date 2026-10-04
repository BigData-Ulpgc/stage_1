"""
Unit tests for the control layer (state manager).

Covers the marks and the recovery of a control file whose last append
was interrupted, as Java's ControlFiles does.
"""

import os
import tempfile
import unittest

from ...main.bigdata.control.state_manager import ControlLayer


class TestControlLayer(unittest.TestCase):
    """Test suite for ControlLayer."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.base_dir = self._tmp.name

    def tearDown(self):
        self._tmp.cleanup()

    def _write(self, name: str, content: bytes) -> str:
        path = os.path.join(self.base_dir, name)
        with open(path, "wb") as f:
            f.write(content)
        return path

    def _read(self, path: str) -> bytes:
        with open(path, "rb") as f:
            return f.read()

    def test_import(self):
        """Verify that ControlLayer can be imported."""
        self.assertTrue(callable(ControlLayer))

    def test_marks_are_appended_once(self):
        control = ControlLayer(base_dir=self.base_dir)
        control.mark_as_downloaded(1342)
        control.mark_as_downloaded(1342)
        control.mark_as_indexed(1342)
        self.assertTrue(control.is_downloaded(1342))
        self.assertTrue(control.is_indexed(1342))
        self.assertEqual(self._read(control.downloaded_file), b"1342\n")

    def test_partial_last_line_is_ignored_and_truncated(self):
        """An append cut at "13" must not count, nor be glued to the next id."""
        path = self._write("downloaded_books.txt", b"1342\n84\n13")
        control = ControlLayer(base_dir=self.base_dir)
        self.assertEqual(control.get_downloaded_books(), {1342, 84})
        self.assertEqual(self._read(path), b"1342\n84\n")

        control.mark_as_downloaded(84)
        control.mark_as_downloaded(11)
        self.assertEqual(self._read(path), b"1342\n84\n11\n")
        self.assertEqual(control.get_downloaded_books(), {1342, 84, 11})

    def test_file_without_any_newline_is_emptied(self):
        path = self._write("indexed_books.txt", b"13")
        control = ControlLayer(base_dir=self.base_dir)
        self.assertEqual(control.get_indexed_books(), set())
        self.assertEqual(self._read(path), b"")

    def test_crlf_and_garbage_lines(self):
        """Windows \\r\\n is accepted; empty lines, garbage and leading zeros are ignored."""
        self._write("downloaded_books.txt", b"1342\r\n\nabc\n0084\n11\n")
        control = ControlLayer(base_dir=self.base_dir)
        self.assertEqual(control.get_downloaded_books(), {1342, 11})


if __name__ == "__main__":
    unittest.main()
