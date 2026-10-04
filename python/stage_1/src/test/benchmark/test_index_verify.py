"""
Unit tests for index_benchmark.verify, the check that runs after every index experiment.

A correct index must pass; an index that loses one posting or answers a query wrongly
must stop the benchmark with a RuntimeError.
"""

import unittest

from ...main.bigdata.benchmark.index_benchmark import TokenizedBook, verify
from ...main.bigdata.datamart.index.in_memory import InMemoryInvertedIndex

QUERIES = ["whale", "ship sea", "island"]


def _books():
    return [
        TokenizedBook(11, frozenset({"island", "sea", "alice"})),
        TokenizedBook(76, frozenset({"island", "river", "whale"})),
        TokenizedBook(2701, frozenset({"whale", "ship", "sea"})),
    ]


class _LosesOnePosting(InMemoryInvertedIndex):
    """Forgets book 2701 in the posting list of "whale"."""

    def postings(self, term):
        ids = super().postings(term)
        return [i for i in ids if not (term == "whale" and i == 2701)]


class TestVerify(unittest.TestCase):

    def _index(self, cls=InMemoryInvertedIndex):
        index = cls()
        for book in _books():
            index.add_document(book.id, book.terms)
        return index

    def test_a_correct_index_passes(self):
        verify(self._index(), _books(), QUERIES, "memory")

    def test_a_missing_posting_is_detected(self):
        with self.assertRaises(RuntimeError) as ctx:
            verify(self._index(_LosesOnePosting), _books(), QUERIES, "broken")
        self.assertIn("whale", str(ctx.exception))

    def test_a_missing_book_is_detected(self):
        index = InMemoryInvertedIndex()
        for book in _books()[:-1]:              # the last book was never added
            index.add_document(book.id, book.terms)
        with self.assertRaises(RuntimeError):
            verify(index, _books(), QUERIES, "incomplete")


if __name__ == "__main__":
    unittest.main()
