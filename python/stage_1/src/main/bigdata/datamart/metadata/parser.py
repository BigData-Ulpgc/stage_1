"""Metadata parser — extracts title, author, release_date, language from headers."""
import re
from typing import Optional

_RE_TITLE = re.compile(r'^Title:\s*(.+)$', re.MULTILINE)
_RE_AUTHOR = re.compile(r'^Author:\s*(.+)$', re.MULTILINE)
_RE_RELEASE_DATE = re.compile(r'^Release date:\s*(.+?)(?:\s*\[.*)?$', re.MULTILINE)
_RE_LANGUAGE = re.compile(r'^Language:\s*(.+)$', re.MULTILINE)


def _first_match(pattern: re.Pattern, text: str) -> Optional[str]:
    """Group 1 of the first match, stripped; None if there is none or it ends up empty."""
    m = pattern.search(text)
    if not m:
        return None
    value = m.group(1).strip()
    return value or None


def parse_metadata(header_text: str) -> dict:
    """Extract metadata fields from a Gutenberg header.

    Returns dict with keys: title, author, release_date, language.
    Values are None when the field is not found or is empty, as in Java.
    """
    return {
        'title': _first_match(_RE_TITLE, header_text),
        'author': _first_match(_RE_AUTHOR, header_text),
        'release_date': _first_match(_RE_RELEASE_DATE, header_text),
        'language': _first_match(_RE_LANGUAGE, header_text),
    }
