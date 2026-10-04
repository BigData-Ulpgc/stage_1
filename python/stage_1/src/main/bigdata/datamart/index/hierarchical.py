import os
import shutil
from pathlib import Path
from typing import List, Set
from .base import InvertedIndex

TMP_SUFFIX = '.tmp'


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
        ids = self._read_ids(self._get_path_for_term(term))
        ids.update(self._pending.get(term, set()))
        return sorted(ids)

    def flush(self) -> None:
        """For each changed term: read its file, merge the new ids and, only if something
        changed, rewrite that file with temp file + move, as Java's HierarchicalFolderIndex."""
        for term, new_ids in self._pending.items():
            path = self._get_path_for_term(term)
            ids = self._read_ids(path)
            before = len(ids)
            ids.update(new_ids)
            if len(ids) != before:  # nothing new: the file is not touched
                self._write_ids(path, ids)
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

    @staticmethod
    def _read_ids(path: Path) -> set[int]:
        """Ids of a term file; empty if it does not exist. A corrupt file raises
        ValueError instead of being treated as empty, so a flush never overwrites it."""
        ids: set[int] = set()
        if path.exists():
            with open(path, 'r', encoding='utf-8') as f:
                for line in f:
                    line = line.strip()
                    if line:
                        ids.add(int(line))
        return ids

    @staticmethod
    def _write_ids(path: Path, ids: set[int]) -> None:
        """One id per line, sorted, in a single write to <term>.txt.tmp, then moved."""
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_name(path.name + TMP_SUFFIX)
        content = ''.join(f"{book_id}\n" for book_id in sorted(ids))
        try:
            with open(tmp, 'w', encoding='utf-8', newline='\n') as f:
                f.write(content)
            os.replace(tmp, path)
        except BaseException:
            tmp.unlink(missing_ok=True)
            raise
