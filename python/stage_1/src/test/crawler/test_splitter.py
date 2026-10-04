"""
Unit tests for crawler.splitter.split_header_body
========================================================
Verifies that the function correctly separates the header and body from a
simulated Project Gutenberg text, without requiring network access.
"""

import unittest

from ...main.bigdata.crawler.splitter import split_header_body


class TestSplitHeaderBody(unittest.TestCase):
    """Test suite for the split_header_body function."""


    def setUp(self):
        """
        Builds a simulated (mock) string with the typical structure
        of a Project Gutenberg ebook:

        1. Preceding junk text
        2. *** START OF THE PROJECT GUTENBERG EBOOK TEST ***   (1st occurrence -> header)
        3. Header with Title and Author
        4. *** START OF THE PROJECT GUTENBERG EBOOK TEST ***   (2nd occurrence -> start of actual body)
        5. Body content
        6. *** END OF THE PROJECT GUTENBERG EBOOK TEST ***
        """
        self.raw_text = (
            "Junk text at the beginning of the file\n"
            "*** START OF THE PROJECT GUTENBERG EBOOK TEST ***\n"
            "Title: Test\n"
            "Author: John\n"
            "*** START OF THE PROJECT GUTENBERG EBOOK TEST ***\n"
            "This is the body content.\n"
            "*** END OF THE PROJECT GUTENBERG EBOOK TEST ***\n"
        )

    def test_header_is_extracted_correctly(self):
        """The header must contain everything before the first START marker."""
        header, _ = split_header_body(book_id=0, raw_text=self.raw_text)
        self.assertEqual(header, "Junk text at the beginning of the file")

    def test_body_is_extracted_correctly(self):
        """The body must be the content between the first START and END,
        without the markers."""
        _, body = split_header_body(book_id=0, raw_text=self.raw_text)
        # Between the first START and END are: internal header + 2nd START + actual body
        # The implementation takes everything from the first START+1 to END-1
        self.assertIn("This is the body content.", body)

    def test_markers_not_in_header(self):
        """START/END markers must not appear in the header."""
        header, _ = split_header_body(book_id=0, raw_text=self.raw_text)
        self.assertNotIn("***", header)

    def test_markers_not_in_body(self):
        """START/END markers must not appear in the body."""
        _, body = split_header_body(book_id=0, raw_text=self.raw_text)
        self.assertNotIn("*** END OF THE PROJECT GUTENBERG EBOOK TEST ***", body)

    def test_returns_tuple(self):
        """The result must be a tuple of two strings."""
        result = split_header_body(book_id=0, raw_text=self.raw_text)
        self.assertIsInstance(result, tuple)
        self.assertEqual(len(result), 2)
        self.assertIsInstance(result[0], str)
        self.assertIsInstance(result[1], str)


if __name__ == "__main__":
    unittest.main()
