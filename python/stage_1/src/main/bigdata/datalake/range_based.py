"""Range-based datalake implementation."""
from __future__ import annotations
import re
from pathlib import Path
from typing import Optional, List

from ..models import RawBook, BookLocation
from .base import Datalake


_RANGE_DIR_PATTERN = re.compile(r'^(\d{5,})-(\d{5,})$')
_FILE_PATTERN = re.compile(r'^(\d+)\.(body|header)\.txt$')


class RangeBasedDatalake(Datalake):
    def name(self) -> str:
        return "range"

    def _get_range_dir(self, book_id: int) -> Path:
        ini = (book_id // 1000) * 1000
        fin = ini + 999
        return self._root / f"{ini:05d}-{fin:05d}"

    def save(self, book: RawBook) -> BookLocation:
        range_dir = self._get_range_dir(book.id)
        header_path = range_dir / f"{book.id}.header.txt"
        body_path = range_dir / f"{book.id}.body.txt"
        
        self._write_file(header_path, book.header)
        self._write_file(body_path, book.body)
        
        return BookLocation(book.id, header_path, body_path)

    def locate(self, book_id: int) -> Optional[BookLocation]:
        range_dir = self._get_range_dir(book_id)
        header_path = range_dir / f"{book_id}.header.txt"
        body_path = range_dir / f"{book_id}.body.txt"
        
        if self._is_complete_book(header_path, body_path):
            return BookLocation(book_id, header_path, body_path)
        return None

    def list_book_ids(self) -> List[int]:
        if not self._root.exists():
            return []
            
        valid_ids = []
        for range_dir in self._root.iterdir():
            if not range_dir.is_dir():
                continue
                
            match = _RANGE_DIR_PATTERN.match(range_dir.name)
            if not match:
                continue
                
            ini_str, fin_str = match.groups()
            ini_val, fin_val = int(ini_str), int(fin_str)
            
            # Find all potential books
            for entry in range_dir.iterdir():
                if entry.is_file() and entry.name.endswith('.body.txt'):
                    file_match = _FILE_PATTERN.match(entry.name)
                    if file_match:
                        book_id = int(file_match.group(1))
                        # Verify book id belongs in this range
                        if ini_val <= book_id <= fin_val:
                            header_path = range_dir / f"{book_id}.header.txt"
                            if self._is_complete_book(header_path, entry):
                                valid_ids.append(book_id)
                                
        return sorted(valid_ids)
