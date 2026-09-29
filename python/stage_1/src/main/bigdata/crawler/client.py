"""
Crawler Module — HTTP Client
=============================
HTTP request logic for Project Gutenberg.
"""

import requests
from typing import Tuple

# Project Gutenberg base URL
GUTENBERG_URL = "https://www.gutenberg.org/cache/epub/{id}/pg{id}.txt"


class DownloadError(Exception):
    """Generic error during book download or processing."""
    pass


def download_raw_text(book_id: int) -> str:
    """
    Downloads the raw text of a Project Gutenberg book by its ID.

    Args:
        book_id: Numeric book ID on Project Gutenberg.

    Returns:
        The complete book text, decoded as UTF-8, with line endings
        normalized to '\\n'.

    Raises:
        DownloadError: If the HTTP download fails (non-200 response code).
    """
    url = GUTENBERG_URL.format(id=book_id)

    response = requests.get(url, timeout=30)
    if response.status_code != 200:
        raise DownloadError(
            f"Error downloading book {book_id}: "
            f"HTTP {response.status_code} for {url}"
        )

    # Decode as UTF-8 and normalize line endings \r\n → \n
    text = response.content.decode("utf-8", errors="replace")
    text = text.replace("\r\n", "\n")

    return text
