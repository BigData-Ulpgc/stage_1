"""
Datalake Module — Book-based Structure
=========================================
Write logic for the book ID folder structure: <ID>/.
"""

import os

# Datalake root relative to the src/ directory
DATALAKE_ROOT = os.path.join(os.path.dirname(__file__), "..", "..", "..", "..", "..", "..", "data", "datalake")


def _write_file(path: str, content: str):
    """Writes a UTF-8 text file, creating the necessary directories."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


def _get_book_paths(book_id: int) -> tuple[str, str]:
    """
    'book' structure: <ID>/body.txt  and  <ID>/header.txt
    """
    folder = os.path.join(DATALAKE_ROOT, "book", str(book_id))
    body_path = os.path.join(folder, "body.txt")
    header_path = os.path.join(folder, "header.txt")
    return header_path, body_path


def save_book_based(
    book_id: int,
    header: str,
    body: str,
) -> tuple[str, str]:
    """
    Saves a book in the Book-based structure (<ID>/).

    Args:
        book_id: Numeric book ID on Project Gutenberg.
        header:  Already processed header text (str, UTF-8).
        body:    Already processed body text (str, UTF-8).

    Returns:
        A (header_path, body_path) tuple with the absolute paths of
        the written files.
    """
    header_path, body_path = _get_book_paths(book_id)
    _write_file(header_path, header)
    _write_file(body_path, body)

    return header_path, body_path
