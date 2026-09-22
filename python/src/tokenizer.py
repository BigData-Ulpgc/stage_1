import os
import re

# ---------------------------------------------------------------------------
# Ruta al archivo de stopwords (relativa a este módulo, Sección 1 del SPEC)
# ---------------------------------------------------------------------------
_STOPWORDS_PATH = os.path.join(
    os.path.dirname(__file__), "..", "..", "shared", "stopwords.txt"
)


def _load_stopwords(path: str) -> frozenset[str]:
    """
    Carga las stopwords desde *path* en un frozenset para búsquedas O(1).

    Reglas (Sección 1 del SPEC):
    - Las líneas vacías o que empiezan por ``#`` se ignoran.
    - Cada palabra se almacena en minúsculas (ya lo están en el fichero,
      pero se aplica .lower() como salvaguarda).
    """
    stopwords: set[str] = set()
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            word = line.strip()
            if word and not word.startswith("#"):
                stopwords.add(word.lower())
    return frozenset(stopwords)


# Cargadas una sola vez al importar el módulo → todas las llamadas
# comparten la misma instancia sin releer el disco.
_STOPWORDS: frozenset[str] = _load_stopwords(_STOPWORDS_PATH)


# ---------------------------------------------------------------------------
# Tokenizador principal (Sección 5 del SPEC)
# ---------------------------------------------------------------------------

def tokenize(text: str) -> set[str]:
    """
    Tokeniza el body de un libro siguiendo los pasos 1-6 de la Sección 5.

    Pasos implementados:
    1-3. ``re.findall(r'[a-zA-Z0-9]+', text)`` extrae solo secuencias de
         letras ASCII (a-z, A-Z) y dígitos (0-9). Cualquier otro carácter
         —espacios, puntuación, apóstrofes, bytes no ASCII— actúa como
         separador natural, sin necesidad de bucles byte a byte.
    4a.  Convertir a minúsculas con ``.lower()``.
    4b.  Descartar tokens de longitud ``< 2``.
    5.   Descartar tokens presentes en el conjunto de stopwords.
    6.   Acumular los tokens válidos en un ``set`` (cada libro aporta el
         *conjunto* de términos, sin frecuencias ni posiciones).

    Args:
        text: Texto completo del body del libro (str, UTF-8).

    Returns:
        Conjunto (``set[str]``) de tokens válidos en minúsculas.
    """
    tokens: set[str] = set()

    for raw_token in re.findall(r"[a-zA-Z0-9]+", text):
        word = raw_token.lower()         # paso 4a: normalizar a minúsculas

        if len(word) < 2:               # paso 4b: descartar longitud < 2
            continue

        if word in _STOPWORDS:          # paso 5: descartar stopwords
            continue

        tokens.add(word)                # paso 6: añadir al conjunto

    return tokens
