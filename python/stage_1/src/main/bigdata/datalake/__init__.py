"""
Datalake Module — Convenience Functions
=======================================
Provides save_to_all_structures which writes to all three datalake
structures simultaneously.
"""

from datetime import datetime

from .time_based import save_time_based
from .book_based import save_book_based
from .range_based import save_range_based


def save_to_all_structures(
    book_id: int,
    header: str,
    body: str,
    now: datetime = None,
) -> dict[str, tuple[str, str]]:
    """
    Saves a book to all three datalake structures simultaneously.

    Returns:
        Dictionary {structure: (header_path, body_path)} with the paths for each
        structure.
    """
    if now is None:
        now = datetime.now()

    return {
        "time":  save_time_based(book_id, header, body, now),
        "book":  save_book_based(book_id, header, body),
        "range": save_range_based(book_id, header, body),
    }
