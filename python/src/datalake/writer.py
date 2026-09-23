import os
from datetime import datetime
from typing import Literal

# Raíz del datalake relativa a la carpeta src/
DATALAKE_ROOT = os.path.join(os.path.dirname(__file__), "..", "..", "..", "data", "datalake")

DatalakeStructure = Literal["time", "book", "range"]


def _write_file(path: str, content: str):
    """Escribe un archivo de texto en UTF-8, creando los directorios necesarios."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


def _get_time_paths(book_id: int, now: datetime) -> tuple[str, str]:
    """
    Estructura 'time': YYYYMMDD/HH/<ID>.body.txt  y  <ID>.header.txt
    """
    date_str = now.strftime("%Y%m%d")
    hour_str = now.strftime("%H")
    folder = os.path.join(DATALAKE_ROOT, "time", date_str, hour_str)
    body_path = os.path.join(folder, f"{book_id}.body.txt")
    header_path = os.path.join(folder, f"{book_id}.header.txt")
    return header_path, body_path


def _get_book_paths(book_id: int) -> tuple[str, str]:
    """
    Estructura 'book': <ID>/body.txt  y  <ID>/header.txt
    """
    folder = os.path.join(DATALAKE_ROOT, "book", str(book_id))
    body_path = os.path.join(folder, "body.txt")
    header_path = os.path.join(folder, "header.txt")
    return header_path, body_path


def _get_range_paths(book_id: int) -> tuple[str, str]:
    """
    Estructura 'range': <INI>-<FIN>/<ID>.body.txt  y  <ID>.header.txt
    INI = (ID // 1000) * 1000, FIN = INI + 999, ambos a 5 dígitos con ceros.
    """
    ini = (book_id // 1000) * 1000
    fin = ini + 999
    folder_name = f"{ini:05d}-{fin:05d}"
    folder = os.path.join(DATALAKE_ROOT, "range", folder_name)
    body_path = os.path.join(folder, f"{book_id}.body.txt")
    header_path = os.path.join(folder, f"{book_id}.header.txt")
    return header_path, body_path


def save_to_datalake(
    book_id: int,
    header: str,
    body: str,
    structure: DatalakeStructure,
    now: datetime = None,
) -> tuple[str, str]:
    """
    Guarda el header y el body de un libro en el datalake con la estructura indicada.

    Args:
        book_id:   ID numérico del libro en Project Gutenberg.
        header:    Texto del header ya procesado (str, UTF-8).
        body:      Texto del body ya procesado (str, UTF-8).
        structure: Estructura de directorios a usar: 'time', 'book' o 'range'.
        now:       Momento de la descarga (datetime). Si es None se usa el instante
                   actual. Solo relevante para la estructura 'time'.

    Returns:
        Una tupla (header_path, body_path) con las rutas absolutas de los archivos
        escritos.

    Raises:
        ValueError: Si la estructura indicada no es válida.
    """
    if now is None:
        now = datetime.now()

    if structure == "time":
        header_path, body_path = _get_time_paths(book_id, now)
    elif structure == "book":
        header_path, body_path = _get_book_paths(book_id)
    elif structure == "range":
        header_path, body_path = _get_range_paths(book_id)
    else:
        raise ValueError(
            f"Estructura de datalake no válida: '{structure}'. "
            "Las opciones son: 'time', 'book', 'range'."
        )

    _write_file(header_path, header)
    _write_file(body_path, body)

    return header_path, body_path


def save_to_all_structures(
    book_id: int,
    header: str,
    body: str,
    now: datetime = None,
) -> dict[str, tuple[str, str]]:
    """
    Guarda el libro en las tres estructuras del datalake simultáneamente.

    Returns:
        Diccionario {estructura: (header_path, body_path)} con las rutas de cada
        estructura.
    """
    if now is None:
        now = datetime.now()

    results = {}
    for structure in ("time", "book", "range"):
        results[structure] = save_to_datalake(book_id, header, body, structure, now)

    return results
