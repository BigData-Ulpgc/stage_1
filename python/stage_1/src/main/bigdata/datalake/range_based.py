"""
Datalake Module — Range-based Structure
==========================================
Write logic for the range-based structure: <INI>-<FIN>/.
"""

import os

# Datalake root relative to the src/ directory
DATALAKE_ROOT = os.path.join(os.path.dirname(__file__), "..", "..", "..", "..", "..", "..", "data", "datalake")


def _write_file(path: str, content: str):
    """Writes a UTF-8 text file, creating the necessary directories."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


def _get_range_paths(book_id: int) -> tuple[str, str]:
    """
    'range' structure: <INI>-<FIN>/<ID>.body.txt and <ID>.header.txt
    INI = (ID // 1000) * 1000, FIN = INI + 999, both zero-padded to 5 digits.
    """
    ini = (book_id // 1000) * 1000
    fin = ini + 999
    folder_name = f"{ini:05d}-{fin:05d}"
    folder = os.path.join(DATALAKE_ROOT, "range", folder_name)
    body_path = os.path.join(folder, f"{book_id}.body.txt")
    header_path = os.path.join(folder, f"{book_id}.header.txt")
    return header_path, body_path


def save_range_based(
    book_id: int,
    header: str,
    body: str,
) -> tuple[str, str]:
    """
    Saves a book in the Range-based structure (<INI>-<FIN>/).

    Args:
        book_id: Numeric book ID on Project Gutenberg.
        header:  Already processed header text (str, UTF-8).
        body:    Already processed body text (str, UTF-8).

    Returns:
        A (header_path, body_path) tuple with the absolute paths of
        the written files.
    """
    header_path, body_path = _get_range_paths(book_id)
    _write_file(header_path, header)
    _write_file(body_path, body)

    return header_path, body_path
