"""
Datamart Module — Tokenizer
===========================
Text tokenizer for the body of Project Gutenberg books.
"""

import os
import re

# ---------------------------------------------------------------------------
# Path to the stopwords file (relative to this module, SPEC Section 1)
# ---------------------------------------------------------------------------
_STOPWORDS_PATH = os.path.join(
    os.path.dirname(__file__), "..", "..", "..", "..", "..", "..", "..", "shared", "stopwords.txt"
)


def _load_stopwords(path: str) -> frozenset[str]:
    """
    Loads stopwords from *path* into a frozenset for O(1) lookups.

    Rules (SPEC Section 1):
    - Empty lines or those starting with ``#`` are ignored.
    - Each word is stored in lowercase (they already are in the file,
      but .lower() is applied as a safeguard).
    """
    stopwords: set[str] = set()
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            word = line.strip()
            if word and not word.startswith("#"):
                stopwords.add(word.lower())
    return frozenset(stopwords)


# Loaded once at module import → all calls share the same instance without
# re-reading from disk.
_STOPWORDS: frozenset[str] = _load_stopwords(_STOPWORDS_PATH)


# ---------------------------------------------------------------------------
# Main tokenizer (SPEC Section 5)
# ---------------------------------------------------------------------------

def tokenize(text: str) -> set[str]:
    """
    Tokenizes a book's body following steps 1-6 of Section 5.

    Implemented steps:
    1-3. ``re.findall(r'[a-zA-Z0-9]+', text)`` extracts only sequences of
         ASCII letters (a-z, A-Z) and digits (0-9). Any other character
         —spaces, punctuation, apostrophes, non-ASCII bytes— acts as a
         natural separator, without the need for byte-by-byte loops.
    4a.  Convert to lowercase with ``.lower()``.
    4b.  Discard tokens with length ``< 2``.
    5.   Discard tokens present in the stopwords set.
    6.   Accumulate valid tokens in a ``set`` (each book contributes a
         set of terms, without frequencies or positions).

    Args:
        text: Complete book body text (str, UTF-8).

    Returns:
        Set (``set[str]``) of valid lowercase tokens.
    """
    tokens: set[str] = set()

    for raw_token in re.findall(r"\w+", text):
        word = raw_token.lower()         # step 4a: normalize to lowercase

        if len(word) < 2:               # step 4b: discard length < 2
            continue

        if word in _STOPWORDS:          # step 5: discard stopwords
            continue

        tokens.add(word)                # step 6: add to set

    return tokens
