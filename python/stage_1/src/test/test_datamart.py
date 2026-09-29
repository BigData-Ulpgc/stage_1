"""
Unit tests for datamart.metadata.parser and datamart.index.tokenizer
=========================================================================
Verifies metadata extraction and tokenization without network or database.
"""

import unittest
from unittest.mock import patch

from ..main.bigdata.datamart.metadata.parser import extract_metadata
from ..main.bigdata.datamart.index import tokenizer


# ==========================================================================
# Metadata Tests
# ==========================================================================


class TestExtractMetadata(unittest.TestCase):
    """Test suite for the parser's extract_metadata function."""

    def test_extracts_title_author_language(self):
        """Given a header with Title, Author and Language, the returned
        dictionary must contain those exact values."""
        header = "Title: Don Quijote\nAuthor: Cervantes\nLanguage: es"
        result = extract_metadata(header)

        self.assertEqual(result["title"], "Don Quijote")
        self.assertEqual(result["author"], "Cervantes")
        self.assertEqual(result["language"], "es")

    def test_missing_release_date_is_none(self):
        """If the header does not contain Release date, the field must be None."""
        header = "Title: Don Quijote\nAuthor: Cervantes\nLanguage: es"
        result = extract_metadata(header)

        self.assertIsNone(result["release_date"])

    def test_returns_all_four_keys(self):
        """The dictionary must always have the 4 expected keys."""
        header = "Title: Don Quijote\nAuthor: Cervantes\nLanguage: es"
        result = extract_metadata(header)

        for key in ("title", "author", "release_date", "language"):
            self.assertIn(key, result)


# ==========================================================================
# Tokenizer Tests
# ==========================================================================


class TestTokenizer(unittest.TestCase):
    """Test suite for the tokenizer's tokenize function.

    The tokenizer uses ``re.findall(r'\\w+', text)`` which captures sequences
    of Unicode characters (including accents, eñes, etc.), digits and
    underscores.  Tokens with length < 2 are discarded.
    """

    @patch.object(tokenizer, "_STOPWORDS", frozenset({"el", "la"}))
    def test_tokenize_with_patched_stopwords(self):
        """Patches _STOPWORDS with {"el", "la"} and verifies tokenization
        of a text with Unicode characters (accents)."""
        text = "El perro, la gata! y el ratón."
        result = tokenizer.tokenize(text)

        # 'el' and 'la' are stopwords → removed
        # 'y' has length < 2 → discarded
        # remaining: 'perro', 'gata', 'ratón'
        expected = {"perro", "gata", "ratón"}
        self.assertEqual(result, expected)

    @patch.object(tokenizer, "_STOPWORDS", frozenset({"el", "la"}))
    def test_tokenize_lowercase(self):
        """All tokens must be in lowercase."""
        text = "El Perro LA GATA"
        result = tokenizer.tokenize(text)

        for token in result:
            self.assertEqual(token, token.lower())

    @patch.object(tokenizer, "_STOPWORDS", frozenset({"el", "la"}))
    def test_tokenize_removes_stopwords(self):
        """The patched stopwords must be removed from the result."""
        text = "el perro la gata"
        result = tokenizer.tokenize(text)

        self.assertNotIn("el", result)
        self.assertNotIn("la", result)

    @patch.object(tokenizer, "_STOPWORDS", frozenset({"el", "la"}))
    def test_tokenize_removes_short_tokens(self):
        """Tokens with length < 2 must be discarded."""
        text = "I a y perro"
        result = tokenizer.tokenize(text)

        self.assertNotIn("i", result)
        self.assertNotIn("a", result)
        self.assertNotIn("y", result)
        self.assertIn("perro", result)


if __name__ == "__main__":
    unittest.main()
