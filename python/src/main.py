"""
Orquestador principal del pipeline de indexación — Stage 1
==========================================================
Une todos los módulos del proyecto para procesar libros de
Project Gutenberg de extremo a extremo:

    book_ids.txt → descarga → datalake → metadatos →
    tokenización → índices invertidos → marca de control
"""

import os
import sys

# ---------------------------------------------------------------------------
# Imports de los módulos propios
# ---------------------------------------------------------------------------
from control_layer import ControlLayer
from datalake.downloader import fetch_book
from datalake.writer import save_to_all_structures
from datamart.metadata import MetadataManager
from datamart.tokenizer import tokenize
from datamart.indices import MonolithicIndex, HierarchicalIndex, MongoIndex

# ---------------------------------------------------------------------------
# Rutas (relativas al directorio de este script → src/)
# ---------------------------------------------------------------------------
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_BOOK_IDS_PATH = os.path.join(_SCRIPT_DIR, "..", "..", "shared", "book_ids.txt")


# ---------------------------------------------------------------------------
# Lectura de IDs
# ---------------------------------------------------------------------------

def load_book_ids(filepath: str) -> list[int]:
    """
    Lee *filepath* y devuelve la lista de IDs numéricos.

    - Ignora líneas vacías o que empiecen por ``#``.
    - Cada línea válida debe contener un entero.
    """
    ids: list[int] = []
    with open(filepath, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            try:
                ids.append(int(line))
            except ValueError:
                print(f"[WARN] Línea ignorada en {filepath}: '{line}'")
    return ids


# ---------------------------------------------------------------------------
# Pipeline principal
# ---------------------------------------------------------------------------

def main() -> None:
    # ── 1. Lectura de IDs ──────────────────────────────────────────────
    print("=" * 65)
    print("[INFO] Iniciando pipeline de indexación")
    print("=" * 65)

    if not os.path.isfile(_BOOK_IDS_PATH):
        print(f"[ERROR] No se encontró el archivo de IDs: {_BOOK_IDS_PATH}")
        sys.exit(1)

    book_ids = load_book_ids(_BOOK_IDS_PATH)
    print(f"[INFO] Libros a procesar: {len(book_ids)} -> {book_ids}\n")

    # ── 2. Inicialización ─────────────────────────────────────────────
    control = ControlLayer()                      # usa ruta por defecto
    metadata = MetadataManager()                  # crea esquema en __init__
    mono_index = MonolithicIndex()
    hier_index = HierarchicalIndex()

    # MongoDB: envolver en try-except para no bloquear el resto
    mongo_index = None
    try:
        mongo_index = MongoIndex()
        print("[INFO] Conexión a MongoDB establecida correctamente.")
    except Exception as e:
        print(f"[WARN] No se pudo conectar a MongoDB: {e}")
        print("[WARN] El índice MongoDB se omitirá durante esta ejecución.\n")

    # ── 3. Bucle de procesamiento ─────────────────────────────────────
    processed = 0
    skipped = 0
    errors = 0

    for book_id in book_ids:
        print("-" * 55)
        print(f"[INFO] Procesando libro {book_id}...")

        # 3a. Filtro — ya indexado
        if control.is_indexed(book_id):
            print(f"[SKIP] Libro {book_id} ya está indexado. Saltando.")
            skipped += 1
            continue

        # 3b. Descarga
        print(f"[INFO] [{book_id}] Descargando desde Project Gutenberg...")
        result = fetch_book(book_id)
        if result is None:
            print(f"[ERROR] [{book_id}] La descarga falló. Saltando.")
            errors += 1
            continue
        header, body = result
        print(f"[INFO] [{book_id}] Descarga completada "
              f"(header: {len(header)} chars, body: {len(body)} chars).")

        # 3c. Datalake
        print(f"[INFO] [{book_id}] Guardando en datalake (3 estructuras)...")
        paths = save_to_all_structures(book_id, header, body)
        book_header_path, book_body_path = paths["book"]
        print(f"[INFO] [{book_id}] Datalake -> OK.")

        # 3d. Metadatos
        print(f"[INFO] [{book_id}] Insertando metadatos en SQLite...")
        metadata.insert_or_update_book(
            book_id=book_id,
            header_text=header,
            body_path=book_body_path,
            header_path=book_header_path,
        )
        print(f"[INFO] [{book_id}] Metadatos -> OK.")

        # 3e. Tokenización
        print(f"[INFO] [{book_id}] Tokenizando body...")
        tokens = tokenize(body)
        print(f"[INFO] [{book_id}] Tokens únicos: {len(tokens)}.")

        # 3f. Índices invertidos
        print(f"[INFO] [{book_id}] Actualizando índice monolítico...")
        mono_index.add_postings(book_id, tokens)

        print(f"[INFO] [{book_id}] Actualizando índice jerárquico...")
        hier_index.add_postings(book_id, tokens)

        if mongo_index is not None:
            print(f"[INFO] [{book_id}] Actualizando índice MongoDB...")
            mongo_index.add_postings(book_id, tokens)
        else:
            print(f"[WARN] [{book_id}] Índice MongoDB omitido (sin conexión).")

        # 3g. Confirmación en la capa de control
        control.mark_as_downloaded(book_id)
        control.mark_as_indexed(book_id)
        print(f"[INFO] [{book_id}] [OK] Libro procesado correctamente.")

        processed += 1

    # ── 4. Cierre ─────────────────────────────────────────────────────
    print("\n" + "=" * 65)
    print("[INFO] Guardando índice monolítico en disco...")
    mono_index.save()
    print("[INFO] Índice monolítico guardado.")

    metadata.close()
    print("[INFO] Conexión SQLite cerrada.")

    if mongo_index is not None:
        mongo_index.close()
        print("[INFO] Conexión MongoDB cerrada.")

    # ── 5. Resumen final ──────────────────────────────────────────────
    print("=" * 65)
    print(f"[INFO] Pipeline finalizado.")
    print(f"       Procesados : {processed}")
    print(f"       Saltados   : {skipped}")
    print(f"       Errores    : {errors}")
    print(f"       Total      : {len(book_ids)}")
    print("=" * 65)


# ---------------------------------------------------------------------------
# Punto de entrada
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    main()
