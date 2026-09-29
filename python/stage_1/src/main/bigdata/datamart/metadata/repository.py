"""
Datamart Module — Metadata Repository
=======================================
SQLite connection logic, table creation and data insertion
for book metadata.
"""

import os
import sqlite3

from datamart.metadata.parser import extract_metadata

# Default datamart path, relative to the src/datamart/metadata/ directory
_DEFAULT_DB_PATH = os.path.join(
    os.path.dirname(__file__), "..", "..", "..", "..", "..", "..", "..", "data", "datamarts", "metadata.db"
)


class MetadataManager:
    """
    Manages the SQLite book metadata database (Datamart).

    Implements Section 4 of SPEC.md:
    - Table ``books`` with the fields: book_id, title, author, language,
      release_date, body_path, header_path.
    - Indexes on ``author`` and ``title``.
    - Idempotent insert/update with INSERT OR REPLACE.
    """

    def __init__(self, db_path: str = _DEFAULT_DB_PATH) -> None:
        """
        Initializes the metadata manager.

        Args:
            db_path: Path to the SQLite file. Intermediate directories
                     are created if they do not exist.
        """
        self._db_path = os.path.abspath(db_path)
        os.makedirs(os.path.dirname(self._db_path), exist_ok=True)
        self._conn = sqlite3.connect(self._db_path)
        self._create_schema()

    # ------------------------------------------------------------------
    # Schema creation (Section 4 · exact SQL queries from the contract)
    # ------------------------------------------------------------------

    def _create_schema(self) -> None:
        """
        Creates the ``books`` table and its two indexes if they do not exist yet.
        Executes exactly the three SQL statements defined in Section 4.
        """
        cursor = self._conn.cursor()
        cursor.executescript(
            """
            CREATE TABLE IF NOT EXISTS books (
                book_id      INTEGER PRIMARY KEY,
                title        TEXT,
                author       TEXT,
                language     TEXT,
                release_date TEXT,
                body_path    TEXT,
                header_path  TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_books_author ON books(author);
            CREATE INDEX IF NOT EXISTS idx_books_title  ON books(title);
            """
        )
        self._conn.commit()

    # ------------------------------------------------------------------
    # Insert / update (Section 4 · INSERT OR REPLACE)
    # ------------------------------------------------------------------

    def insert_or_update_book(
        self,
        book_id: int,
        header_text: str,
        body_path: str,
        header_path: str,
    ) -> None:
        """
        Inserts or updates a book record in the ``books`` table.

        Extracts metadata from the ``header_text`` and executes an
        ``INSERT OR REPLACE`` with the 7 table fields using
        parameterized queries to prevent SQL injection and ensure
        proper escaping of special characters.

        Args:
            book_id:     Numeric book ID on Project Gutenberg.
            header_text: Complete book header text.
            body_path:   Absolute path to the body file in the datalake.
            header_path: Absolute path to the header file in the datalake.
        """
        meta = extract_metadata(header_text)

        self._conn.execute(
            """
            INSERT OR REPLACE INTO books
                (book_id, title, author, language, release_date, body_path, header_path)
            VALUES
                (?, ?, ?, ?, ?, ?, ?)
            """,
            (
                book_id,
                meta["title"],
                meta["author"],
                meta["language"],
                meta["release_date"],
                body_path,
                header_path,
            ),
        )
        self._conn.commit()

    # ------------------------------------------------------------------
    # Connection lifecycle management
    # ------------------------------------------------------------------

    def close(self) -> None:
        """Closes the database connection."""
        self._conn.close()

    def __enter__(self) -> "MetadataManager":
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        self.close()
