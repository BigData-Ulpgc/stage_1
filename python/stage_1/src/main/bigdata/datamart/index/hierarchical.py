import os
import shutil
from pathlib import Path
from typing import List, Set
from .base import InvertedIndex

class HierarchicalFolderIndex(InvertedIndex):
    def __init__(self, root: Path):
        self._root = Path(root)
        self._pending: dict[str, set[int]] = {}

    def name(self) -> str:
        return "hierarchical"
        
    def _get_path_for_term(self, term: str) -> Path:
        first_char = term[0].upper()
        return self._root / first_char / f"{term}.txt"

    def add_document(self, book_id: int, terms: Set[str]) -> None:
        for term in terms:
            self._pending.setdefault(term, set()).add(book_id)

    def postings(self, term: str) -> List[int]:
        ids = set(self._pending.get(term, set()))
        path = self._get_path_for_term(term)
        
        if path.exists():
            with open(path, 'r', encoding='utf-8') as f:
                for line in f:
                    line = line.strip()
                    if line:
                        ids.add(int(line))
                        
        return sorted(ids)

    def flush(self) -> None:
        for term, new_ids in self._pending.items():
            path = self._get_path_for_term(term)
            path.parent.mkdir(parents=True, exist_ok=True)
            
            existing_ids = []
            if path.exists():
                with open(path, 'r', encoding='utf-8') as f:
                    for line in f:
                        line = line.strip()
                        if line:
                            existing_ids.append(int(line))
            
            sorted_new = sorted(new_ids)
            
            if not existing_ids or sorted_new[0] > existing_ids[-1]:
                with open(path, 'a', encoding='utf-8', newline='\n', buffering=131072) as f:
                    for book_id in sorted_new:
                        f.write(f"{book_id}\n")
            else:
                merged_ids = sorted(set(existing_ids).union(new_ids))
                with open(path, 'w', encoding='utf-8', newline='\n', buffering=131072) as f:
                    for book_id in merged_ids:
                        f.write(f"{book_id}\n")
                        
        self._pending.clear()

    def clear(self) -> None:
        self._pending.clear()
        if self._root.exists():
            shutil.rmtree(self._root)

    def disk_usage_bytes(self) -> int:
        total = 0
        if self._root.exists():
            for root, dirs, files in os.walk(self._root):
                for f in files:
                    fp = os.path.join(root, f)
                    if not os.path.islink(fp):
                        total += os.path.getsize(fp)
        return total
