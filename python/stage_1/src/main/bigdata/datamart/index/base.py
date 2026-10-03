"""Abstract base class for inverted index implementations."""
from __future__ import annotations
from abc import ABC, abstractmethod
from typing import List, Set


class InvertedIndex(ABC):
    """Contract for any inverted index backend."""

    @abstractmethod
    def name(self) -> str: ...

    @abstractmethod
    def add_document(self, book_id: int, terms: Set[str]) -> None: ...

    @abstractmethod
    def postings(self, term: str) -> List[int]: ...

    @abstractmethod
    def flush(self) -> None: ...

    @abstractmethod
    def clear(self) -> None: ...

    @abstractmethod
    def disk_usage_bytes(self) -> int: ...

    def close(self) -> None:
        pass
