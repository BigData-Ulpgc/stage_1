import os
import re
import sqlite3
from typing import Optional

# Ruta por defecto del datamart, relativa a la carpeta src/
_DEFAULT_DB_PATH = os.path.join(
    os.path.dirname(__file__), "..", "..", "data", "datamarts", "metadata.db"
)

# ---------------------------------------------------------------------------
# Expresiones regulares de la Sección 4 del contrato (multilínea)
# ---------------------------------------------------------------------------
_RE_TITLE = re.compile(r"^Title:\s*(.+)$", re.MULTILINE)
_RE_AUTHOR = re.compile(r"^Author:\s*(.+)$", re.MULTILINE)
_RE_RELEASE_DATE = re.compile(r"^Release date:\s*(.+?)(\s*\[.*)?$", re.MULTILINE)
_RE_LANGUAGE = re.compile(r"^Language:\s*(.+)$", re.MULTILINE)


class MetadataManager:
    """
    Gestiona la base de datos SQLite de metadatos de libros (Datamart).

    Implementa la Sección 4 del SPEC.md:
    - Tabla ``books`` con los campos: book_id, title, author, language,
      release_date, body_path, header_path.
    - Índices sobre ``author`` y ``title``.
    - Extracción de metadatos mediante regex multilínea sobre el header.
    - Inserción/actualización idempotente con INSERT OR REPLACE.
    """

    def __init__(self, db_path: str = _DEFAULT_DB_PATH) -> None:
        """
        Inicializa el gestor de metadatos.

        Args:
            db_path: Ruta al archivo SQLite. Se crean los directorios
                     intermedios si no existen.
        """
        self._db_path = os.path.abspath(db_path)
        os.makedirs(os.path.dirname(self._db_path), exist_ok=True)
        self._conn = sqlite3.connect(self._db_path)
        self._create_schema()

    # ------------------------------------------------------------------
    # Creación del esquema (Sección 4 · consultas SQL exactas del contrato)
    # ------------------------------------------------------------------

    def _create_schema(self) -> None:
        """
        Crea la tabla ``books`` y sus dos índices si no existen todavía.
        Ejecuta exactamente las tres sentencias SQL definidas en la Sección 4.
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
    # Extracción de metadatos (Sección 4 · regex multilínea)
    # ------------------------------------------------------------------

    def _extract_metadata(self, header_text: str) -> dict:
        """
        Extrae los 4 campos de metadatos del texto del header usando las
        expresiones regulares definidas en la Sección 4 del contrato.

        Args:
            header_text: Contenido íntegro del header del libro.

        Returns:
            Diccionario con las claves ``title``, ``author``,
            ``release_date`` y ``language``. El valor es ``None`` cuando
            el campo no se encuentra en el header (→ NULL en la BD).
        """
        def _first_match(pattern: re.Pattern, text: str) -> Optional[str]:
            """Devuelve la primera coincidencia del grupo 1, con strip(), o None."""
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

    # ------------------------------------------------------------------
    # Inserción / actualización (Sección 4 · INSERT OR REPLACE)
    # ------------------------------------------------------------------

    def insert_or_update_book(
        self,
        book_id: int,
        header_text: str,
        body_path: str,
        header_path: str,
    ) -> None:
        """
        Inserta o actualiza el registro de un libro en la tabla ``books``.

        Extrae los metadatos del ``header_text`` y ejecuta un
        ``INSERT OR REPLACE`` con los 7 campos de la tabla usando
        consultas parametrizadas para evitar inyecciones SQL y garantizar
        el escape correcto de cualquier carácter especial.

        Args:
            book_id:     ID numérico del libro en Project Gutenberg.
            header_text: Texto completo del header del libro.
            body_path:   Ruta absoluta al archivo body en el datalake.
            header_path: Ruta absoluta al archivo header en el datalake.
        """
        meta = self._extract_metadata(header_text)

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
    # Gestión del ciclo de vida de la conexión
    # ------------------------------------------------------------------

    def close(self) -> None:
        """Cierra la conexión a la base de datos."""
        self._conn.close()

    def __enter__(self) -> "MetadataManager":
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        self.close()
