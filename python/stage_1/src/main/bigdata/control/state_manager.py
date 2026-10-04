import os
import re
from typing import Set

# Same criterion as the datalake: "0" or no leading zeros, at most 9 digits
_VALID_ID = re.compile(r'^(0|[1-9][0-9]{0,8})$')


class ControlLayer:
    """
    Manages the state of processed books (downloaded and indexed),
    as defined in Section 8 of the common contract (SPEC.md).

    Only COMPLETE lines (ending in \\n) count, as in Java's ControlFiles: if the program
    died halfway through writing "1342\\n" and "13" was left, that fragment is ignored,
    and it is trimmed from the file when the layer is created, so the next append
    does not glue it to another id ("13" + "84\\n" = "1384").
    """

    def __init__(self, base_dir: str = "../data/control"):
        self.base_dir = base_dir
        self.downloaded_file = os.path.join(self.base_dir, "downloaded_books.txt")
        self.indexed_file = os.path.join(self.base_dir, "indexed_books.txt")
        self._ensure_dir()
        self._truncate_partial_line(self.downloaded_file)
        self._truncate_partial_line(self.indexed_file)

    def _ensure_dir(self):
        """Ensures the control directory exists."""
        os.makedirs(self.base_dir, exist_ok=True)
        # Create empty files if they do not exist
        if not os.path.exists(self.downloaded_file):
            open(self.downloaded_file, 'a').close()
        if not os.path.exists(self.indexed_file):
            open(self.indexed_file, 'a').close()

    @staticmethod
    def _complete_part(content: bytes) -> bytes:
        """The content up to its last \\n (b"" if there is none)."""
        return content[:content.rfind(b"\n") + 1]

    @classmethod
    def _truncate_partial_line(cls, filepath: str) -> None:
        """Cuts a last line left without \\n by an interrupted append."""
        with open(filepath, "rb") as f:
            content = f.read()
        complete = cls._complete_part(content)
        if len(complete) < len(content):
            with open(filepath, "r+b") as f:
                f.truncate(len(complete))

    def _read_ids(self, filepath: str) -> Set[int]:
        """Reads the complete lines of a file and returns its set of IDs."""
        ids = set()
        if os.path.exists(filepath):
            with open(filepath, "rb") as f:
                complete = self._complete_part(f.read())
            for line in complete.decode("utf-8").split("\n"):
                line = line.strip()  # also removes a Windows \r
                if _VALID_ID.match(line):
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
