"""Book-based datalake implementation."""
from __future__ import annotations
import re
from pathlib import Path
from typing import Optional, List

from ..models import RawBook, BookLocation
from .base import Datalake


_CANONICAL_ID = re.compile(r'^(0|[1-9][0-9]{0,8})$')


class BookBasedDatalake(Datalake):
    def name(self) -> str:
        return "book"

    def save(self, book: RawBook) -> BookLocation:
        book_dir = self._root / str(book.id)
        header_path = book_dir / "header.txt"
        body_path = book_dir / "body.txt"
        
        self._write_file(header_path, book.header)
        self._write_file(body_path, book.body)
        
        return BookLocation(book.id, header_path, body_path)

    def locate(self, book_id: int) -> Optional[BookLocation]:
        book_dir = self._root / str(book_id)
        header_path = book_dir / "header.txt"
        body_path = book_dir / "body.txt"
        
        if self._is_complete_book(header_path, body_path):
            return BookLocation(book_id, header_path, body_path)
        return None

    def list_book_ids(self) -> List[int]:
        if not self._root.exists():
            return []
            
        valid_ids = []
        for entry in self._root.iterdir():
            if entry.is_dir() and _CANONICAL_ID.match(entry.name):
                header_path = entry / "header.txt"
                body_path = entry / "body.txt"
                if self._is_complete_book(header_path, body_path):
                    valid_ids.append(int(entry.name))
        
        return sorted(valid_ids)
