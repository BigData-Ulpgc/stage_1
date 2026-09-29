"""
Datamart Module — Metadata Parser
=================================
Regex logic to extract the Title, Author,
Release Date and Language from the book header text.
"""

import re
from typing import Optional

# ---------------------------------------------------------------------------
# Regular expressions from Section 4 of the contract (multiline)
# ---------------------------------------------------------------------------
_RE_TITLE = re.compile(r"^Title:\s*(.+)$", re.MULTILINE)
_RE_AUTHOR = re.compile(r"^Author:\s*(.+)$", re.MULTILINE)
_RE_RELEASE_DATE = re.compile(r"^Release date:\s*(.+?)(\s*\[.*)?$", re.MULTILINE)
_RE_LANGUAGE = re.compile(r"^Language:\s*(.+)$", re.MULTILINE)


def extract_metadata(header_text: str) -> dict:
    """
    Extracts the 4 metadata fields from the header text using the
    regular expressions defined in Section 4 of the contract.

    Args:
        header_text: Complete book header content.

    Returns:
        Dictionary with keys ``title``, ``author``,
        ``release_date`` and ``language``. The value is ``None`` when
        the field is not found in the header (→ NULL in the DB).
    """
    def _first_match(pattern: re.Pattern, text: str) -> Optional[str]:
        """Returns the first match of group 1, with strip(), or None."""
        match = pattern.search(text)
        if match:
            return match.group(1).strip()
        return None

    return {
        "title": _first_match(_RE_TITLE, header_text),
        "author": _first_match(_RE_AUTHOR, header_text),
        "release_date": _first_match(_RE_RELEASE_DATE, header_text),
        "language": _first_match(_RE_LANGUAGE, header_text),
    }
