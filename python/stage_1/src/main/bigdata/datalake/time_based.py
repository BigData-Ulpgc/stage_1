"""
Datalake Module — Time-based Structure
=========================================
Write logic for the date-and-time structure: YYYYMMDD/HH/<ID>.
"""

import os
from datetime import datetime

# Datalake root relative to the src/ directory
DATALAKE_ROOT = os.path.join(os.path.dirname(__file__), "..", "..", "..", "..", "..", "..", "data", "datalake")


def _write_file(path: str, content: str):
    """Writes a UTF-8 text file, creating the necessary directories."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


def _get_time_paths(book_id: int, now: datetime) -> tuple[str, str]:
    """
    'time' structure: YYYYMMDD/HH/<ID>.body.txt and <ID>.header.txt
    """
    date_str = now.strftime("%Y%m%d")
    hour_str = now.strftime("%H")
    folder = os.path.join(DATALAKE_ROOT, "time", date_str, hour_str)
    body_path = os.path.join(folder, f"{book_id}.body.txt")
    header_path = os.path.join(folder, f"{book_id}.header.txt")
    return header_path, body_path


def save_time_based(
    book_id: int,
    header: str,
    body: str,
    now: datetime = None,
) -> tuple[str, str]:
    """
    Saves a book in the Time-based structure (YYYYMMDD/HH/).

    Args:
        book_id: Numeric book ID on Project Gutenberg.
        header:  Already processed header text (str, UTF-8).
        body:    Already processed body text (str, UTF-8).
        now:     Download timestamp (datetime). If None, the current
                 time is used.

    Returns:
        A (header_path, body_path) tuple with the absolute paths of
        the written files.
    """
    if now is None:
        now = datetime.now()

    header_path, body_path = _get_time_paths(book_id, now)
    _write_file(header_path, header)
    _write_file(body_path, body)

    return header_path, body_path
