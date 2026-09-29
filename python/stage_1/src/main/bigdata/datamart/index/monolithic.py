"""
Datamart Module — Monolithic Inverted Index
============================================
Contains only the MonolithicIndex class (inverted_index.json).
"""

from __future__ import annotations

import json
import os

# ---------------------------------------------------------------------------
# Base paths (relative to this module → src/datamart/index/)
# ---------------------------------------------------------------------------
_DATAMARTS_ROOT = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "..", "..", "..", "..", "..", "..", "data", "datamarts")
)

_MONOLITHIC_PATH = os.path.join(_DATAMARTS_ROOT, "inverted_index.json")


class MonolithicIndex:
    """
    Monolithic inverted index stored in a single JSON file.

    Format: ``{"term": [id1, id2, ...], ...}``
    Lists are sorted in ascending order and contain no duplicates.
    """

    def __init__(self, json_path: str = _MONOLITHIC_PATH) -> None:
        """
        Initializes the index by reading the existing JSON (if any).

        The in-memory state is stored in ``self._index``:
        ``dict[str, set[int]]`` to facilitate duplicate-free insertions.
        When persisting, it is converted to a sorted ``dict[str, list[int]]``.

        Args:
            json_path: Path to the index JSON file.
        """
        self._path = json_path
        self._index: dict[str, set[int]] = {}

        if os.path.isfile(self._path):
            with open(self._path, encoding="utf-8") as fh:
                raw: dict[str, list[int]] = json.load(fh)
            # Convert lists → sets for O(1) insertions
            self._index = {term: set(ids) for term, ids in raw.items()}

    # ------------------------------------------------------------------

    def add_postings(self, book_id: int, terms: set[str]) -> None:
        """
        Adds *book_id* to the posting list of each term in *terms*.

        Uses internal sets to guarantee no duplicates.
        Does not write to disk; call :meth:`save` explicitly.

        Args:
            book_id: Numeric book ID.
            terms:   Set of tokens from the book (tokenizer output).
        """
        for term in terms:
            if term not in self._index:
                self._index[term] = set()
            self._index[term].add(book_id)

    # ------------------------------------------------------------------

    def save(self) -> None:
        """
        Flushes the index to disk in JSON format.

        Each posting list is serialized as an ascending-sorted
        ``list[int]``. Creates intermediate directories if they do not exist.
        """
        os.makedirs(os.path.dirname(self._path), exist_ok=True)
        serializable = {
            term: sorted(ids)
            for term, ids in self._index.items()
        }
        with open(self._path, "w", encoding="utf-8") as fh:
            json.dump(serializable, fh, ensure_ascii=False)

    # ------------------------------------------------------------------

    def __enter__(self) -> "MonolithicIndex":
        return self

    def __exit__(self, *_) -> None:
        self.save()
