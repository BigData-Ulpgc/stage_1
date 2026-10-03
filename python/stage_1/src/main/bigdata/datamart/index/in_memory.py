from typing import List, Set
from .base import InvertedIndex

class InMemoryInvertedIndex(InvertedIndex):
    def __init__(self):
        self._index: dict[str, set[int]] = {}

    def name(self) -> str:
        return "memory"

    def add_document(self, book_id: int, terms: Set[str]) -> None:
        for term in terms:
            self._index.setdefault(term, set()).add(book_id)

    def postings(self, term: str) -> List[int]:
        return sorted(self._index.get(term, set()))

    def flush(self) -> None:
        pass

    def clear(self) -> None:
        self._index.clear()

    def disk_usage_bytes(self) -> int:
        return 0
