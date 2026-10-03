"""Core data models — simple Python dataclasses replacing Java records."""
from __future__ import annotations
from dataclasses import dataclass
from pathlib import Path
from typing import Optional


@dataclass(frozen=True, slots=True)
class RawBook:
    """A downloaded book with its id, header text, and body text."""
    id: int
    header: str
    body: str


@dataclass(frozen=True, slots=True)
class BookLocation:
    """Where a book is stored on disk."""
    id: int
    header_path: Path
    body_path: Path


@dataclass(frozen=True, slots=True)
class BookMetadata:
    """Metadata extracted from a book's header."""
    book_id: int
    title: Optional[str]
    author: Optional[str]
    language: Optional[str]
    release_date: Optional[str]
    body_path: Optional[str]
    header_path: Optional[str]
