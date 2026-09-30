"""
Datamart Module — Hierarchical Inverted Index
=============================================
Contains only the HierarchicalIndex class (inverted_index/<LETTER>/<term>.txt).
"""

from __future__ import annotations

import os

# ---------------------------------------------------------------------------
# Base paths (relative to this module → src/datamart/index/)
# ---------------------------------------------------------------------------
_DATAMARTS_ROOT = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "..", "..", "..", "..", "..", "..", "data", "datamarts")
)

_HIERARCHICAL_BASE = os.path.join(_DATAMARTS_ROOT, "inverted_index")


class HierarchicalIndex:
    """
    Hierarchical inverted index: one ``.txt`` file per term.

    Path: ``<base>/<FIRST_UPPERCASE_LETTER>/<term>.txt``
    If the term starts with a digit, the subfolder is ``#``.
    Each file contains one ID per line, sorted in ascending order,
    without duplicates.
    """

    def __init__(self, base_path: str = _HIERARCHICAL_BASE) -> None:
        """
        Args:
            base_path: Root directory of the hierarchical index.
        """
        self._base = base_path

    # ------------------------------------------------------------------

    @staticmethod
    def _term_path(base: str, term: str) -> str:
        """
        Returns the path to the term's ``.txt`` file.

        The subfolder is the first letter uppercased, or ``#`` if
        the term starts with a digit.
        """
        first = term[0]
        subfolder = "#" if first.isdigit() else first.upper()
        return os.path.join(base, subfolder, f"{term}.txt")

    # ------------------------------------------------------------------

    def add_postings(self, book_id: int, terms: set[str]) -> None:
        """
        Adds *book_id* to the posting list of each term.

        Reads the existing file (if any), adds the ID, deduplicates,
        sorts in ascending order, and overwrites the file.

        Args:
            book_id: Numeric book ID.
            terms:   Set of tokens from the book.
        """
        for term in terms:
            path = self._term_path(self._base, term)
            os.makedirs(os.path.dirname(path), exist_ok=True)

            # Read existing IDs
            existing: set[int] = set()
            if os.path.isfile(path):
                with open(path, encoding="utf-8") as fh:
                    for line in fh:
                        line = line.strip()
                        if line:
                            existing.add(int(line))

            # Add the new ID and save sorted
            existing.add(book_id)
            with open(path, "w", encoding="utf-8") as fh:
                fh.write("\n".join(str(i) for i in sorted(existing)))
                fh.write("\n")  # trailing newline
