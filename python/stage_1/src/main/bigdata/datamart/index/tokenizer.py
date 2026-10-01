"""
Datamart Module — Tokenizer
===========================
Text tokenizer for the body of Project Gutenberg books.
"""

import os

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
    - Each word is stored in lowercase.
    """
    stopwords: set[str] = set()
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            word = line.strip()
            if word and not word.startswith("#"):
                stopwords.add(word.lower())
    return frozenset(stopwords)


# Loaded once at module import
_STOPWORDS: frozenset[str] = _load_stopwords(_STOPWORDS_PATH)


# ---------------------------------------------------------------------------
# Main tokenizer (SPEC Section 5)
# ---------------------------------------------------------------------------

def tokenize(text: str) -> set[str]:
    """
    Tokenizes a book's body following steps 1-6 of Section 5.

    1. Iterate character by character.
    2. A-Z to lowercase. a-z and 0-9 are part of the token.
    3. Any other character (spaces, punctuation, non-ASCII/UTF-8) is a separator.
    4. Discard tokens with length < 2.
    5. Discard tokens present in the stopwords set.
    6. Accumulate valid tokens in a ``set`` to ensure terms are unique.

    Args:
        text: Complete book body text (str, UTF-8).

    Returns:
        Set (``set[str]``) of valid lowercase tokens.
    """
    tokens: set[str] = set()
    current_token = []

    for char in text:
        # A-Z -> a-z
        if 'A' <= char <= 'Z':
            current_token.append(char.lower())
        # a-z, 0-9
        elif ('a' <= char <= 'z') or ('0' <= char <= '9'):
            current_token.append(char)
        # Any other character is a separator
        else:
            if current_token:
                if len(current_token) >= 2:
                    word = "".join(current_token)
                    if word not in _STOPWORDS:
                        tokens.add(word)
                current_token.clear()

    # Process the final token if text didn't end with a separator
    if current_token:
        if len(current_token) >= 2:
            word = "".join(current_token)
            if word not in _STOPWORDS:
                tokens.add(word)

    return tokens
