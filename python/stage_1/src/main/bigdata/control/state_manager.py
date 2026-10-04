import os
from typing import Set

class ControlLayer:
    """
    Manages the state of processed books (downloaded and indexed),
    as defined in Section 8 of the common contract (SPEC.md).
    """

    def __init__(self, base_dir: str = "../data/control"):
        self.base_dir = base_dir
        self.downloaded_file = os.path.join(self.base_dir, "downloaded_books.txt")
        self.indexed_file = os.path.join(self.base_dir, "indexed_books.txt")
        self._ensure_dir()
        
    def _ensure_dir(self):
        """Ensures the control directory exists."""
        os.makedirs(self.base_dir, exist_ok=True)
        # Create empty files if they do not exist
        if not os.path.exists(self.downloaded_file):
            open(self.downloaded_file, 'a').close()
        if not os.path.exists(self.indexed_file):
            open(self.indexed_file, 'a').close()

    def _read_ids(self, filepath: str) -> Set[int]:
        """Reads a file and returns a set of IDs."""
        ids = set()
        if os.path.exists(filepath):
            with open(filepath, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if line.isdigit():
                        ids.add(int(line))
        return ids

    def _add_id(self, filepath: str, book_id: int):
        """Safely appends an ID to the specified file."""
        with open(filepath, "a", encoding="utf-8", newline="\n") as f:
            f.write(f"{book_id}\n")

    def get_downloaded_books(self) -> Set[int]:
        """Returns the set of IDs of already downloaded books."""
        return self._read_ids(self.downloaded_file)

    def is_downloaded(self, book_id: int) -> bool:
        """Checks whether a book has already been downloaded."""
        return book_id in self.get_downloaded_books()

    def mark_as_downloaded(self, book_id: int):
        """
        Marks a book as downloaded by appending it to downloaded_books.txt.
        Must be called ONLY after the file has been written successfully.
        """
        if not self.is_downloaded(book_id):
            self._add_id(self.downloaded_file, book_id)

    def get_indexed_books(self) -> Set[int]:
        """Returns the set of IDs of already indexed books."""
        return self._read_ids(self.indexed_file)

    def is_indexed(self, book_id: int) -> bool:
        """Checks whether a book has already been indexed."""
        return book_id in self.get_indexed_books()

    def mark_as_indexed(self, book_id: int):
        """
        Marks a book as indexed by appending it to indexed_books.txt.
        Must be called ONLY after the index has been updated successfully.
        """
        if not self.is_indexed(book_id):
            self._add_id(self.indexed_file, book_id)
