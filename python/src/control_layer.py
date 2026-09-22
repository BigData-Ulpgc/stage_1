import os
from typing import Set

class ControlLayer:
    """
    Gestión del estado de los libros procesados (descargados e indexados),
    según la Sección 8 del contrato común (SPEC.md).
    """

    def __init__(self, base_dir: str = "../data/control"):
        self.base_dir = base_dir
        self.downloaded_file = os.path.join(self.base_dir, "downloaded_books.txt")
        self.indexed_file = os.path.join(self.base_dir, "indexed_books.txt")
        self._ensure_dir()
        
    def _ensure_dir(self):
        """Asegura que el directorio de control exista."""
        os.makedirs(self.base_dir, exist_ok=True)
        # Crear archivos vacíos si no existen
        if not os.path.exists(self.downloaded_file):
            open(self.downloaded_file, 'a').close()
        if not os.path.exists(self.indexed_file):
            open(self.indexed_file, 'a').close()

    def _read_ids(self, filepath: str) -> Set[int]:
        """Lee un archivo y devuelve un conjunto de IDs."""
        ids = set()
        if os.path.exists(filepath):
            with open(filepath, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if line.isdigit():
                        ids.add(int(line))
        return ids

    def _add_id(self, filepath: str, book_id: int):
        """Añade de forma segura (append) un ID al archivo especificado."""
        with open(filepath, "a", encoding="utf-8") as f:
            f.write(f"{book_id}\n")

    def get_downloaded_books(self) -> Set[int]:
        """Obtiene el conjunto de IDs de los libros ya descargados."""
        return self._read_ids(self.downloaded_file)

    def is_downloaded(self, book_id: int) -> bool:
        """Verifica si un libro ya está descargado."""
        return book_id in self.get_downloaded_books()

    def mark_as_downloaded(self, book_id: int):
        """
        Marca un libro como descargado añadiéndolo a downloaded_books.txt.
        Debe llamarse SÓLO después de que el archivo se haya escrito correctamente.
        """
        if not self.is_downloaded(book_id):
            self._add_id(self.downloaded_file, book_id)

    def get_indexed_books(self) -> Set[int]:
        """Obtiene el conjunto de IDs de los libros ya indexados."""
        return self._read_ids(self.indexed_file)

    def is_indexed(self, book_id: int) -> bool:
        """Verifica si un libro ya está indexado."""
        return book_id in self.get_indexed_books()

    def mark_as_indexed(self, book_id: int):
        """
        Marca un libro como indexado añadiéndolo a indexed_books.txt.
        Debe llamarse SÓLO después de que el índice se haya actualizado correctamente.
        """
        if not self.is_indexed(book_id):
            self._add_id(self.indexed_file, book_id)
