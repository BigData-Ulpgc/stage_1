# Contrato común (Java · Python · C)

El enunciado exige que las tres implementaciones **procesen el mismo dataset, usen las
mismas reglas de preprocesado y produzcan salidas equivalentes**. Este documento fija esas
reglas. Cualquier cambio se acuerda entre los tres y se refleja aquí.

> Estado: **propuesta inicial**. Revisar y validar en grupo.

---

## 1. Dataset y consultas

| Fichero                   | Contenido                                              |
|---------------------------|--------------------------------------------------------|
| `shared/book_ids.txt`     | IDs de Gutenberg a descargar, uno por línea (mismo orden en los 3 lenguajes) |
| `shared/queries.txt`      | Carga de consultas para el benchmark, una por línea    |
| `shared/stopwords.txt`    | Stopwords (una por línea, en minúsculas)               |

Líneas vacías o que empiezan por `#` se ignoran en los tres ficheros.

## 2. Descarga y separación header/body

- URL: `https://www.gutenberg.org/cache/epub/<ID>/pg<ID>.txt`
- Marcadores (se acepta `THE` o `THIS`):
  - inicio: `*** START OF THE PROJECT GUTENBERG EBOOK` / `*** START OF THIS PROJECT GUTENBERG EBOOK`
  - fin:    `*** END OF THE PROJECT GUTENBERG EBOOK`   / `*** END OF THIS PROJECT GUTENBERG EBOOK`
- Si falta alguno de los dos marcadores, el libro **se descarta** (no se guarda ni se marca como descargado).
- `header` = texto antes del marcador de inicio, con `trim`.
- `body`   = texto desde el **final de la línea** del marcador de inicio hasta el comienzo del marcador de fin, con `trim`.
- El footer se descarta.
- Codificación: UTF-8. Saltos de línea normalizados a `\n` (`\r\n` → `\n`).

## 3. Estructuras del datalake a comparar

Raíz: `<data>/datalake/<estructura>/`

| Estructura | Ruta del body | Ruta del header |
|------------|---------------|-----------------|
| `time`     | `YYYYMMDD/HH/<ID>.body.txt` | `YYYYMMDD/HH/<ID>.header.txt` |
| `book`     | `<ID>/body.txt`             | `<ID>/header.txt`             |
| `range`    | `<INI>-<FIN>/<ID>.body.txt` | `<INI>-<FIN>/<ID>.header.txt` |

- `time`: fecha/hora local de la descarga (formato 24h).
- `range`: `INI = (ID / 1000) * 1000`, `FIN = INI + 999`, ambos con 5 dígitos rellenos con ceros
  (ej. ID 1342 → `01000-01999`).

## 4. Metadatos

Extraídos del header, **primera coincidencia**, sólo la primera línea del valor, con `trim`:

| Campo         | Regex (multilínea)          |
|---------------|-----------------------------|
| `title`       | `^Title:\s*(.+)$`           |
| `author`      | `^Author:\s*(.+)$`          |
| `release_date`| `^Release date:\s*(.+?)(\s*\[.*)?$` |
| `language`    | `^Language:\s*(.+)$`        |

Campo ausente → `NULL`.

Tabla (SQLite, `<data>/datamarts/metadata.db`):

```sql
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
```

## 5. Tokenizador (idéntico en los 3 lenguajes)

Pensado para que en C se pueda implementar byte a byte sin librerías Unicode:

1. Recorrer el body carácter a carácter.
2. `A-Z` → pasar a minúscula. `a-z` y `0-9` forman parte del token.
3. **Cualquier otro carácter** (espacios, puntuación, apóstrofes, bytes/caracteres no ASCII) es separador.
4. Descartar tokens de longitud `< 2`.
5. Descartar tokens presentes en `shared/stopwords.txt`.
6. Para el índice, cada libro aporta el **conjunto** de términos (sin frecuencias ni posiciones).

## 6. Estructuras del índice invertido a comparar

Raíz: `<data>/datamarts/`

| Estructura     | Formato |
|----------------|---------|
| `monolithic`   | `inverted_index.json` → `{"term": [id1, id2, ...], ...}` |
| `hierarchical` | `inverted_index/<PRIMERA_LETRA_MAYÚSCULA>/<term>.txt`, un ID por línea |
| `mongo`        | BD `search_engine`, colección `inverted_index`, documentos `{"term": "...", "postings": [ids]}`, índice único sobre `term` |

Las posting lists se guardan **ordenadas ascendentemente y sin duplicados**.

## 7. Consultas

- La consulta se tokeniza con el mismo tokenizador (sección 5).
- Semántica **AND**: resultado = intersección de las posting lists de todos los términos.
- Resultado = lista de IDs ordenada ascendentemente.

## 8. Capa de control

- `<data>/control/downloaded_books.txt` y `<data>/control/indexed_books.txt`, un ID por línea.
- Un ID sólo se añade **después** de que el fichero/índice se haya escrito correctamente
  (garantiza recuperación sin pérdidas ni duplicados).

## 9. Formato de resultados del benchmark

Todos los lenguajes escriben CSV con la misma cabecera en `benchmarks/results/<lenguaje>_<experimento>.csv`:

```
language,experiment,structure,dataset_size,repetition,metric,value,unit
java,index_build,monolithic,100,1,elapsed,1234.5,ms
```

- `experiment`: `datalake_write`, `datalake_lookup`, `datalake_incremental`, `datalake_recovery`,
  `datalake_storage`, `metadata_insert`, `metadata_query`, `index_build`, `index_query`,
  `index_update`, `index_memory`, `index_disk`.
- Metodología común: **N_WARMUP = 2** repeticiones descartadas y **N_RUNS = 5** medidas.
- Las descargas de red se miden aparte: para los benchmarks de escritura/índice se parte de los
  libros ya descargados en `sample_dataset/` para que la red no contamine los tiempos.
