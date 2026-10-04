"""SQLite metadata repository."""
import sqlite3
from pathlib import Path
from typing import Optional, List

from ...models import BookMetadata

class MetadataRepository:
    def __init__(self, db_path: Path, with_indexes: bool = True):
        self._path = Path(db_path)
        self._path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(str(self._path))
        
        cursor = self._conn.cursor()
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS books (
                book_id INTEGER PRIMARY KEY,
                title TEXT,
                author TEXT,
                language TEXT,
                release_date TEXT,
                body_path TEXT,
                header_path TEXT
            )
        ''')
        
        if with_indexes:
            cursor.execute('CREATE INDEX IF NOT EXISTS idx_books_author ON books(author)')
            cursor.execute('CREATE INDEX IF NOT EXISTS idx_books_title ON books(title)')
            
        self._conn.commit()

    def save_all(self, rows: list[tuple]):
        self._conn.executemany('''
            INSERT OR REPLACE INTO books
            VALUES (?, ?, ?, ?, ?, ?, ?)
        ''', rows)
        self._conn.commit()
        
    def _row_to_metadata(self, row: tuple) -> BookMetadata:
        return BookMetadata(
            book_id=row[0],
            title=row[1],
            author=row[2],
            language=row[3],
            release_date=row[4],
            body_path=row[5],
            header_path=row[6]
        )

    def find_by_id(self, book_id: int) -> Optional[BookMetadata]:
        cursor = self._conn.cursor()
        cursor.execute('SELECT * FROM books WHERE book_id = ?', (book_id,))
        row = cursor.fetchone()
        if row:
            return self._row_to_metadata(row)
        return None

    def find_by_author(self, author: str) -> List[BookMetadata]:
        cursor = self._conn.cursor()
        cursor.execute('SELECT * FROM books WHERE author = ? ORDER BY book_id', (author,))
        return [self._row_to_metadata(row) for row in cursor.fetchall()]

    def find_by_title(self, title: str) -> List[BookMetadata]:
        cursor = self._conn.cursor()
        cursor.execute('SELECT * FROM books WHERE title = ? ORDER BY book_id', (title,))
        return [self._row_to_metadata(row) for row in cursor.fetchall()]

    def close(self):
        self._conn.close()

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()
