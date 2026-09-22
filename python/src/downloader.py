import re
import requests
from typing import Optional, Tuple

# URL base de Project Gutenberg
GUTENBERG_URL = "https://www.gutenberg.org/cache/epub/{id}/pg{id}.txt"

# Expresiones regulares para los marcadores de inicio y fin (ambas variantes)
START_MARKER_RE = re.compile(
    r"\*\*\* START OF (THE|THIS) PROJECT GUTENBERG EBOOK.*",
    re.IGNORECASE
)
END_MARKER_RE = re.compile(
    r"\*\*\* END OF (THE|THIS) PROJECT GUTENBERG EBOOK",
    re.IGNORECASE
)


class DownloadError(Exception):
    """Error genérico durante la descarga o el procesamiento de un libro."""
    pass


class MarkerNotFoundError(DownloadError):
    """Se lanza cuando faltan los marcadores de inicio o fin en el texto del libro."""
    pass


def download_book(book_id: int) -> Tuple[str, str]:
    """
    Descarga un libro de Project Gutenberg por su ID y lo divide en header y body,
    según la Sección 2 del SPEC.md.

    Args:
        book_id: ID numérico del libro en Project Gutenberg.

    Returns:
        Una tupla (header, body) con el texto correspondiente a cada parte,
        decodificado en UTF-8, con saltos de línea normalizados a '\\n' y
        aplicando .strip() a ambas partes.

    Raises:
        DownloadError: Si la descarga HTTP falla (código de respuesta no 200).
        MarkerNotFoundError: Si el texto descargado no contiene ambos marcadores
                             de inicio y fin válidos.
    """
    url = GUTENBERG_URL.format(id=book_id)

    response = requests.get(url, timeout=30)
    if response.status_code != 200:
        raise DownloadError(
            f"Error al descargar el libro {book_id}: "
            f"HTTP {response.status_code} para {url}"
        )

    # Decodificar en UTF-8 y normalizar saltos de línea \r\n → \n
    text = response.content.decode("utf-8", errors="replace")
    text = text.replace("\r\n", "\n")

    # Dividir en líneas para buscar los marcadores
    lines = text.split("\n")

    start_line_index = None
    end_line_index = None

    for i, line in enumerate(lines):
        if start_line_index is None and START_MARKER_RE.search(line):
            start_line_index = i
        elif start_line_index is not None and END_MARKER_RE.search(line):
            end_line_index = i
            break  # Primer marcador de fin tras el marcador de inicio

    # Verificar que ambos marcadores estén presentes
    if start_line_index is None:
        raise MarkerNotFoundError(
            f"Libro {book_id}: no se encontró el marcador de INICIO. Libro descartado."
        )
    if end_line_index is None:
        raise MarkerNotFoundError(
            f"Libro {book_id}: no se encontró el marcador de FIN. Libro descartado."
        )

    # header = todo antes del marcador de inicio
    header = "\n".join(lines[:start_line_index]).strip()

    # body = desde el final de la línea del marcador de inicio
    #        hasta el comienzo del marcador de fin (excluido)
    body = "\n".join(lines[start_line_index + 1:end_line_index]).strip()

    return header, body


def fetch_book(book_id: int) -> Optional[Tuple[str, str]]:
    """
    Versión conveniente de download_book que devuelve None en caso de error
    en lugar de lanzar una excepción, facilitando su uso en pipelines.

    Args:
        book_id: ID numérico del libro en Project Gutenberg.

    Returns:
        Una tupla (header, body) o None si el libro no pudo procesarse.
    """
    try:
        return download_book(book_id)
    except MarkerNotFoundError as e:
        print(f"[WARN] {e}")
        return None
    except DownloadError as e:
        print(f"[ERROR] {e}")
        return None
    except Exception as e:
        print(f"[ERROR] Error inesperado al procesar el libro {book_id}: {e}")
        return None
