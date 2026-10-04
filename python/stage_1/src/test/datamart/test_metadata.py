"""
Unit tests for datamart.metadata.parser
========================================
Verifies metadata extraction without network or database.
"""

import unittest

from ...main.bigdata.datamart.metadata.parser import parse_metadata


class TestParseMetadata(unittest.TestCase):
    """Test suite for the parser's parse_metadata function."""

    def test_extracts_title_author_language(self):
        """Given a header with Title, Author and Language, the returned
        dictionary must contain those exact values."""
        header = "Title: Don Quijote\nAuthor: Cervantes\nLanguage: es"
        result = parse_metadata(header)

        self.assertEqual(result["title"], "Don Quijote")
        self.assertEqual(result["author"], "Cervantes")
        self.assertEqual(result["language"], "es")

    def test_missing_release_date_is_none(self):
        """If the header does not contain Release date, the field must be None."""
        header = "Title: Don Quijote\nAuthor: Cervantes\nLanguage: es"
        result = parse_metadata(header)

        self.assertIsNone(result["release_date"])

    def test_returns_all_four_keys(self):
        """The dictionary must always have the 4 expected keys."""
        header = "Title: Don Quijote\nAuthor: Cervantes\nLanguage: es"
        result = parse_metadata(header)

        for key in ("title", "author", "release_date", "language"):
            self.assertIn(key, result)


if __name__ == "__main__":
    unittest.main()
