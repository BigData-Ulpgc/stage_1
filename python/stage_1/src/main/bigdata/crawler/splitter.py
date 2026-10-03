"""
Crawler Module — Text Splitter
==============================
Logic to find Project Gutenberg START/END markers
and split raw text into header and body.
"""

import re
from typing import Optional, Tuple

# Regular expressions for the start and end markers (both variants)
START_MARKER_RE = re.compile(
    r"\*\*\* START OF (THE|THIS) PROJECT GUTENBERG EBOOK.*",
    re.IGNORECASE
)
END_MARKER_RE = re.compile(
    r"\*\*\* END OF (THE|THIS) PROJECT GUTENBERG EBOOK",
    re.IGNORECASE
)


class MarkerNotFoundError(Exception):
    """Raised when the start or end markers are missing from the book text."""
    pass


def split_header_body(book_id: int, raw_text: str) -> Tuple[str, str]:
    """
    Splits a book's raw text into header and body by locating the
    Project Gutenberg start and end markers.

    Args:
        book_id:  Numeric book ID (for error messages).
        raw_text: Complete downloaded book text.

    Returns:
        A (header, body) tuple with the text for each part,
        with .strip() applied to both.

    Raises:
        MarkerNotFoundError: If the text does not contain both valid
                             start and end markers.
    """
    lines = raw_text.split("\n")

    start_line_index = None
    end_line_index = None

    for i, line in enumerate(lines):
        if start_line_index is None and START_MARKER_RE.search(line):
            start_line_index = i
        elif start_line_index is not None and END_MARKER_RE.search(line):
            end_line_index = i
            break  # First end marker after the start marker

    # Verify that both markers are present
    if start_line_index is None:
        raise MarkerNotFoundError(
            f"Book {book_id}: START marker not found. Book discarded."
        )
    if end_line_index is None:
        raise MarkerNotFoundError(
            f"Book {book_id}: END marker not found. Book discarded."
        )

    # header = everything before the start marker
    header = "\n".join(lines[:start_line_index]).strip()

    # body = from the end of the start marker line
    #        to the beginning of the end marker (excluded)
    body = "\n".join(lines[start_line_index + 1:end_line_index]).strip()

    return header, body


def fetch_book(book_id: int) -> Optional[Tuple[str, str]]:
    """
    Downloads a Project Gutenberg book and splits it into header and body.

    Convenience wrapper that combines client + splitter and returns None
    on error instead of raising an exception, simplifying use in
    pipelines.

    Args:
        book_id: Numeric book ID in Project Gutenberg.

    Returns:
        A (header, body) tuple or None if the book could not be processed.
    """
    from src.main.bigdata.crawler.client import download_raw_text, DownloadError

    try:
        raw_text = download_raw_text(book_id)
        return split_header_body(book_id, raw_text)
    except MarkerNotFoundError as e:
        print(f"[WARN] {e}")
        return None
    except DownloadError as e:
        print(f"[ERROR] {e}")
        return None
    except Exception as e:
        print(f"[ERROR] Unexpected error processing book {book_id}: {e}")
        return None


def fetch_book_offline(book_id: int, offline_dir: str) -> Optional[Tuple[str, str]]:
    """
    Reads a book from a local file ``pg<ID>.txt`` inside *offline_dir*
    and splits it into header and body — no network requests.

    Args:
        book_id:     Numeric book ID.
        offline_dir: Path to the directory containing the raw text files.

    Returns:
        A (header, body) tuple or None if the file is missing or invalid.
    """
    from pathlib import Path

    file_path = Path(offline_dir) / f"pg{book_id}.txt"
    if not file_path.is_file():
        print(f"[ERROR] Offline file not found: {file_path}")
        return None

    try:
        raw_text = file_path.read_text(encoding="utf-8", errors="replace")
        raw_text = raw_text.replace("\r\n", "\n")
        return split_header_body(book_id, raw_text)
    except MarkerNotFoundError as e:
        print(f"[WARN] {e}")
        return None
    except Exception as e:
        print(f"[ERROR] Unexpected error reading offline book {book_id}: {e}")
        return None
