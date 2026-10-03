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

## 10. Benchmark datasets and sizes (agreed 2026-10-02)

Agreed by the group after the first Java runs. Comparisons between languages use only the data
described here.

### 10.1 Real books (datalake and index experiments)

- **Dataset:** the 200 ids in `shared/book_ids.txt` (the first 15 are the original ones). Every one
  was checked to download with both START/END markers.
- **No network inside a measurement:** each implementation downloads the books once with its own
  `pipeline` into a `book` datalake (section 3), and the benchmarks read them from there. This takes
  the place of the `sample_dataset/` folder mentioned in section 9 for the benchmarks.
  `sample_dataset/` holds only the 15 original books, for quick tests (see its `README.md`).
- **Order of the books:** `book_ids.txt` order is the *download* order (section 1). Benchmarks
  instead take the books in **ascending book id order**, and a size N means **the N books with the
  lowest ids**, the same as Java's `BenchmarkBooks.fromDatalake`. Every N is then a prefix of the
  next one, and all languages measure exactly the same books.

| Experiments | Structures | Sizes (N) |
|---|---|---|
| `datalake_write`, `datalake_lookup`, `datalake_incremental`, `datalake_recovery`, `datalake_storage` | `book`, `range`, `time` | 200 |
| `index_build`, `index_query`, `index_update`, `index_memory`, `index_disk` | `monolithic`, `hierarchical`, `mongo` (when a server is available) | 50, 100, 200 |

**Reference values** (from the Java run, `index_disk`). Every implementation must get exactly these
numbers for the same N. Any difference means a different set of books, or a tokenizer that does not
follow section 5, and must be fixed before comparing any times.

| N | Distinct terms | Postings |
|---|---|---|
| 50 | 58,834 | 360,970 |
| 100 | 78,820 | 759,087 |
| 200 | 129,356 | 1,581,064 |

### 10.2 Synthetic metadata (metadata experiments)

200 rows are too few to see SQLite scale, so `metadata_insert` and `metadata_query` use generated
rows. The generator uses no random numbers, so every language produces identical data:

- For `i = 0 … N-1`:
  - `book_id = i + 1`
  - `title = "Title " + (i / 2)` and `author = "Author " + (i / 10)`, with integer division
  - `language = "English"` and `release_date = "January 1, 2000"`
  - `body_path = "datalake/book/<book_id>/body.txt"` and
    `header_path = "datalake/book/<book_id>/header.txt"`
- Every author has 10 books and every title 2, whatever N is. As N grows, only the table grows, not
  the size of each query's answer.
- **Sizes:** N = 1,000, 10,000 and 100,000.
- **Structures:** `sqlite` (the schema of section 4) and `sqlite_no_index` (the same schema without
  `idx_books_author` and `idx_books_title`).

### 10.3 Other synthetic data

Synthetic *books* for the datalake and index experiments depend on each language's random number
generator, and those generators differ even with the same seed. Such results may be kept as a
per-language reference, but they are **not** compared across languages.

### 10.4 Where results go

- `benchmarks/results/real/<language>_<experiment>.csv`: experiments on the real books (10.1).
- `benchmarks/results/synthetic/<language>_<experiment>.csv`: experiments on synthetic data (10.2,
  10.3).
- The CSV format is the one in section 9. Each implementation may write there directly, or archive
  its runs into these folders by hand.
