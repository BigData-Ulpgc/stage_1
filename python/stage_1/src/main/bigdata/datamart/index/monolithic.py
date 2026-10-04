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
        tmp_path = self._tmp_path()

        export_data = {
            term: sorted(list(ids))
            for term, ids in sorted(self._index.items())
        }

        try:
            with open(tmp_path, 'w', encoding='utf-8', newline='\n') as f:
                f.write(json.dumps(export_data, separators=(',', ':')))
            os.replace(tmp_path, self._path)
        except BaseException:
            tmp_path.unlink(missing_ok=True)
            raise

    def clear(self) -> None:
        self._index.clear()
        self._path.unlink(missing_ok=True)
        self._tmp_path().unlink(missing_ok=True)

    def _tmp_path(self) -> Path:
        """inverted_index.json.tmp, the same name Java uses."""
        return self._path.with_name(self._path.name + '.tmp')

    def disk_usage_bytes(self) -> int:
        if self._path.exists():
            return self._path.stat().st_size
        return 0
