"""Base class for all datalakes."""
from __future__ import annotations
from abc import ABC, abstractmethod
from pathlib import Path
import os
import shutil
import re
from typing import Optional, List

from ..models import RawBook, BookLocation


class Datalake(ABC):
    def __init__(self, root: Path | str):
        self._root = Path(root)

    @abstractmethod
    def name(self) -> str:
        pass

    @abstractmethod
    def save(self, book: RawBook) -> BookLocation:
        pass

    @abstractmethod
    def locate(self, book_id: int) -> Optional[BookLocation]:
        pass

    @abstractmethod
    def list_book_ids(self) -> List[int]:
        pass

    @staticmethod
    def _write_file(path: Path, content: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, 'w', encoding='utf-8', newline='\n', buffering=131072) as f:
            f.write(content)

    @staticmethod
    def _is_complete_book(header_path: Path, body_path: Path) -> bool:
        return header_path.is_file() and body_path.is_file()
