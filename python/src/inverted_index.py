"""
Módulo de Índice Invertido — Sección 6 del SPEC.md
====================================================
Tres implementaciones con la misma interfaz pública:

    add_postings(book_id: int, terms: set[str]) → None

Las posting lists se almacenan **ordenadas ascendentemente y sin duplicados**
en los tres backends.

Estructuras (raíz: <data>/datamarts/):
  - MonolithicIndex   → inverted_index.json
  - HierarchicalIndex → inverted_index/<LETRA_MAYÚSCULA>/<term>.txt
  - MongoIndex        → BD search_engine, colección inverted_index
"""

from __future__ import annotations

import json
import os
from typing import Optional

# ---------------------------------------------------------------------------
# Rutas base (relativas a este módulo → src/)
# ---------------------------------------------------------------------------
_DATAMARTS_ROOT = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "..", "data", "datamarts")
)

_MONOLITHIC_PATH = os.path.join(_DATAMARTS_ROOT, "inverted_index.json")
_HIERARCHICAL_BASE = os.path.join(_DATAMARTS_ROOT, "inverted_index")

# ---------------------------------------------------------------------------
# Guardián de pymongo: el módulo carga aunque pymongo no esté instalado.
# ---------------------------------------------------------------------------
try:
    from pymongo import MongoClient, ASCENDING
    from pymongo.operations import UpdateOne
    _PYMONGO_AVAILABLE = True
except ImportError:
    _PYMONGO_AVAILABLE = False


# ===========================================================================
# 1. MonolithicIndex — inverted_index.json
# ===========================================================================

class MonolithicIndex:
    """
    Índice invertido monolítico almacenado en un único archivo JSON.

    Formato: ``{"term": [id1, id2, ...], ...}``
    Las listas están ordenadas ascendentemente y no contienen duplicados.
    """

    def __init__(self, json_path: str = _MONOLITHIC_PATH) -> None:
        """
        Inicializa el índice leyendo el JSON existente (si lo hay).

        El estado en memoria se guarda en ``self._index``:
        ``dict[str, set[int]]`` para facilitar inserciones sin duplicados.
        Al persistir se convierte a ``dict[str, list[int]]`` ordenado.

        Args:
            json_path: Ruta al archivo JSON del índice.
        """
        self._path = json_path
        self._index: dict[str, set[int]] = {}

        if os.path.isfile(self._path):
            with open(self._path, encoding="utf-8") as fh:
                raw: dict[str, list[int]] = json.load(fh)
            # Convertir listas → sets para inserciones O(1)
            self._index = {term: set(ids) for term, ids in raw.items()}

    # ------------------------------------------------------------------

    def add_postings(self, book_id: int, terms: set[str]) -> None:
        """
        Añade *book_id* a la posting list de cada término en *terms*.

        Usa sets internos para garantizar ausencia de duplicados.
        No escribe en disco: llama a :meth:`save` explícitamente.

        Args:
            book_id: ID numérico del libro.
            terms:   Conjunto de tokens del libro (salida del tokenizador).
        """
        for term in terms:
            if term not in self._index:
                self._index[term] = set()
            self._index[term].add(book_id)

    # ------------------------------------------------------------------

    def save(self) -> None:
        """
        Vuelca el índice a disco en formato JSON.

        Cada posting list se serializa como ``list[int]`` ordenada
        ascendentemente. Crea los directorios intermedios si no existen.
        """
        os.makedirs(os.path.dirname(self._path), exist_ok=True)
        serializable = {
            term: sorted(ids)
            for term, ids in self._index.items()
        }
        with open(self._path, "w", encoding="utf-8") as fh:
            json.dump(serializable, fh, ensure_ascii=False)

    # ------------------------------------------------------------------

    def __enter__(self) -> "MonolithicIndex":
        return self

    def __exit__(self, *_) -> None:
        self.save()


# ===========================================================================
# 2. HierarchicalIndex — inverted_index/<LETRA>/<term>.txt
# ===========================================================================

class HierarchicalIndex:
    """
    Índice invertido jerárquico: un archivo ``.txt`` por término.

    Ruta: ``<base>/<PRIMERA_LETRA_MAYÚSCULA>/<term>.txt``
    Si el término empieza por dígito, la subcarpeta es ``#``.
    Cada archivo contiene un ID por línea, ordenado ascendentemente,
    sin duplicados.
    """

    def __init__(self, base_path: str = _HIERARCHICAL_BASE) -> None:
        """
        Args:
            base_path: Directorio raíz del índice jerárquico.
        """
        self._base = base_path

    # ------------------------------------------------------------------

    @staticmethod
    def _term_path(base: str, term: str) -> str:
        """
        Devuelve la ruta al archivo ``.txt`` del término.

        La subcarpeta es la primera letra en mayúsculas o ``#`` si
        el término empieza por dígito.
        """
        first = term[0]
        subfolder = "#" if first.isdigit() else first.upper()
        return os.path.join(base, subfolder, f"{term}.txt")

    # ------------------------------------------------------------------

    def add_postings(self, book_id: int, terms: set[str]) -> None:
        """
        Añade *book_id* a la posting list de cada término.

        Lee el archivo existente (si lo hay), añade el ID, deduplica,
        ordena ascendentemente y sobrescribe el archivo.

        Args:
            book_id: ID numérico del libro.
            terms:   Conjunto de tokens del libro.
        """
        for term in terms:
            path = self._term_path(self._base, term)
            os.makedirs(os.path.dirname(path), exist_ok=True)

            # Leer IDs existentes
            existing: set[int] = set()
            if os.path.isfile(path):
                with open(path, encoding="utf-8") as fh:
                    for line in fh:
                        line = line.strip()
                        if line:
                            existing.add(int(line))

            # Añadir el nuevo ID y guardar ordenado
            existing.add(book_id)
            with open(path, "w", encoding="utf-8") as fh:
                fh.write("\n".join(str(i) for i in sorted(existing)))
                fh.write("\n")  # salto de línea final


# ===========================================================================
# 3. MongoIndex — BD search_engine, colección inverted_index
# ===========================================================================

class MongoIndex:
    """
    Índice invertido almacenado en MongoDB.

    - Base de datos: ``search_engine``
    - Colección:     ``inverted_index``
    - Documentos:    ``{"term": str, "postings": [int, ...]}``
    - Índice único sobre ``term`` (garantiza un documento por término).

    Las posting lists se mantienen ordenadas ascendentemente mediante
    el modificador ``$push … $each … $sort``.

    Requiere ``pymongo``. Si no está instalado, lanza
    :class:`RuntimeError` al instanciar.
    """

    def __init__(
        self,
        host: str = "localhost",
        port: int = 27017,
        db_name: str = "search_engine",
        collection_name: str = "inverted_index",
    ) -> None:
        """
        Conecta con MongoDB y garantiza el índice único sobre ``term``.

        Args:
            host:            Host del servidor MongoDB.
            port:            Puerto del servidor MongoDB.
            db_name:         Nombre de la base de datos.
            collection_name: Nombre de la colección.

        Raises:
            RuntimeError: Si ``pymongo`` no está disponible en el entorno.
        """
        if not _PYMONGO_AVAILABLE:
            raise RuntimeError(
                "pymongo no está instalado. Ejecuta: pip install pymongo"
            )

        self._client = MongoClient(host, port)
        self._col = self._client[db_name][collection_name]

        # Índice único sobre "term" (Sección 6 del SPEC)
        self._col.create_index("term", unique=True)

    # ------------------------------------------------------------------

    def add_postings(self, book_id: int, terms: set[str]) -> None:
        """
        Añade *book_id* a la posting list de cada término en lote
        (``bulk_write``) para maximizar el rendimiento.

        Cada operación ``UpdateOne`` usa:
        - ``upsert=True``   → crea el documento si el término no existe.
        - ``$push … $each … $sort: 1`` → inserta el ID manteniendo el
          orden ascendente. La deduplicación queda garantizada porque
          ``add_postings`` se invoca exactamente una vez por libro (el
          ``control_layer`` impide re-indexar un libro ya procesado).

        Args:
            book_id: ID numérico del libro.
            terms:   Conjunto de tokens del libro.
        """
        if not terms:
            return

        operations = [
            UpdateOne(
                {"term": term},
                {
                    "$push": {
                        "postings": {
                            "$each": [book_id],
                            "$sort": 1,         # mantiene orden ascendente
                        }
                    }
                },
                upsert=True,
            )
            for term in terms
        ]

        self._col.bulk_write(operations, ordered=False)

    # ------------------------------------------------------------------

    def close(self) -> None:
        """Cierra la conexión con MongoDB."""
        self._client.close()

    def __enter__(self) -> "MongoIndex":
        return self

    def __exit__(self, *_) -> None:
        self.close()
