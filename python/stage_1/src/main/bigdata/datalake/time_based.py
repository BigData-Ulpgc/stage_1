"""Time-based datalake implementation."""
from __future__ import annotations
import re
from datetime import datetime
from pathlib import Path
from typing import Optional, List, Callable

from ..models import RawBook, BookLocation
from .base import Datalake


_DAY_PATTERN = re.compile(r'^\d{8}$')
_HOUR_PATTERN = re.compile(r'^([01]\d|2[0-3])$')
_FILE_PATTERN = re.compile(r'^(\d+)\.(body|header)\.txt$')


class TimeBasedDatalake(Datalake):
    def __init__(self, root: Path | str, clock: Optional[Callable[[], datetime]] = None):
        super().__init__(root)
        self._clock = clock if clock is not None else datetime.now

    def name(self) -> str:
        return "time"

    def save(self, book: RawBook) -> BookLocation:
        now = self._clock()
        day_str = now.strftime('%Y%m%d')
        hour_str = now.strftime('%H')
        
        time_dir = self._root / day_str / hour_str
        header_path = time_dir / f"{book.id}.header.txt"
        body_path = time_dir / f"{book.id}.body.txt"
        
        self._write_file(header_path, book.header)
        self._write_file(body_path, book.body)
        
        return BookLocation(book.id, header_path, body_path)

    def locate(self, book_id: int) -> Optional[BookLocation]:
        if not self._root.exists():
            return None
            
        # Search newest first
        day_dirs = sorted([d for d in self._root.iterdir() if d.is_dir() and _DAY_PATTERN.match(d.name)], reverse=True)
        for day_dir in day_dirs:
            hour_dirs = sorted([h for h in day_dir.iterdir() if h.is_dir() and _HOUR_PATTERN.match(h.name)], reverse=True)
            for hour_dir in hour_dirs:
                header_path = hour_dir / f"{book_id}.header.txt"
                body_path = hour_dir / f"{book_id}.body.txt"
                if self._is_complete_book(header_path, body_path):
                    return BookLocation(book_id, header_path, body_path)
                    
        return None

    def list_book_ids(self) -> List[int]:
        if not self._root.exists():
            return []
            
        book_ids = set()
        
        for day_dir in self._root.iterdir():
            if day_dir.is_dir() and _DAY_PATTERN.match(day_dir.name):
                for hour_dir in day_dir.iterdir():
                    if hour_dir.is_dir() and _HOUR_PATTERN.match(hour_dir.name):
                        for entry in hour_dir.iterdir():
                            if entry.is_file() and entry.name.endswith('.body.txt'):
                                file_match = _FILE_PATTERN.match(entry.name)
                                if file_match:
                                    book_id = int(file_match.group(1))
                                    header_path = hour_dir / f"{book_id}.header.txt"
                                    if self._is_complete_book(header_path, entry):
                                        book_ids.add(book_id)
                                        
        return sorted(list(book_ids))
