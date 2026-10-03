import json
import os
import shutil
from pathlib import Path
from typing import List, Set
from .base import InvertedIndex

class MonolithicJsonIndex(InvertedIndex):
    def __init__(self, path: Path):
        self._path = Path(path)
        self._index: dict[str, set[int]] = {}
        
        if self._path.exists():
            with open(self._path, 'r', encoding='utf-8') as f:
                data = json.load(f)
                for term, ids in data.items():
                    self._index[term] = set(ids)

    def name(self) -> str:
        return "monolithic"

    def add_document(self, book_id: int, terms: Set[str]) -> None:
        for term in terms:
            self._index.setdefault(term, set()).add(book_id)

    def postings(self, term: str) -> List[int]:
        return sorted(self._index.get(term, set()))

    def flush(self) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        tmp_path = self._path.with_suffix('.tmp')
        
        export_data = {
            term: sorted(list(ids))
            for term, ids in sorted(self._index.items())
        }
        
        with open(tmp_path, 'w', encoding='utf-8', buffering=131072) as f:
            json.dump(export_data, f, separators=(',', ':'))
            
        os.replace(tmp_path, self._path)

    def clear(self) -> None:
        self._index.clear()
        if self._path.exists():
            self._path.unlink()

    def disk_usage_bytes(self) -> int:
        if self._path.exists():
            return self._path.stat().st_size
        return 0
