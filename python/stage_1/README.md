# Python module – user guide

The Python implementation of the Stage 1 data layer. It downloads books from Project Gutenberg,
splits each one into header and body, stores them in a datalake, extracts their metadata into
SQLite and builds an inverted index that answers AND searches. It also runs the benchmarks that
compare the storage structures.

The rules shared with the Java and C++ modules (split markers, folder layouts, tokenizer, index
formats, CSV format) are in [`../../shared/SPEC.md`](../../shared/SPEC.md). The design and the
benchmark results are discussed in the report,
[`docs/Stage1_Python_Report.pdf`](docs/Stage1_Python_Report.pdf). Its LaTeX source, and the scripts
that draw its charts and tables from `benchmarks/results/`, are in [`docs/report/`](docs/report/).

## 1. Requirements

| Tool | Version |
|---|---|
| Python | 3.10 or newer |
| pip | any recent version |
| MongoDB | optional, only for the `mongo` index structure |

The only libraries outside the standard library are `requests` (downloads) and `pymongo` (Mongo
index), listed in [`../requirements.txt`](../requirements.txt). The tests also need `pytest`.

MongoDB is not needed to test or run the module. Without a server, the pipeline prints a warning
and skips the Mongo index, and the benchmarks measure the other two structures. To include it,
start the group's server from the repository root:

```bash
docker compose up -d
```

## 2. Setup and test

All commands are run from this `python/stage_1/` folder.

```bash
python -m venv venv
source venv/bin/activate              # Windows: venv\Scripts\activate
pip install -r ../requirements.txt pytest
pytest src/test
```

`pytest` runs the 28 tests under `src/test/`, which mirrors the packages of `src/main/bigdata/`.
They need neither network nor MongoDB, and they write only to temporary folders.

## 3. Commands

```bash
python -m src.main.bigdata.main <command>
```

| Command | What it does | Network |
|---|---|---|
| `pipeline [steps]` | Processes up to `steps` books of `shared/book_ids.txt`, in its order (all of them by default). A book already in `indexed_books.txt` is skipped and does not count as a step. For every other book: download, split, save to **the three datalake structures**, store its metadata, tokenize, add it to **the three indexes** (Mongo only with a server), flush them, and mark it in the control layer. | yes |
| `pipeline [steps] --offline-source <dir>` | The same pipeline, reading `<dir>/pg<ID>.txt` instead of downloading. Everything after fetching a book is identical. | no |
| `search <words...>` | AND search on the monolithic index: the ids of the books that contain every word. The query is tokenized like the books. | no |
| `status` | Books in the dataset, downloaded, indexed, and pending (not indexed yet). | no |

### Example

```bash
python -m src.main.bigdata.main pipeline 200     # the whole dataset
python -m src.main.bigdata.main search whale island
python -m src.main.bigdata.main status
```

The pipeline ends with a summary (`Processed`, `Skipped`, `Errors`, `Total`). With the 200 books,
`status` shows 200 downloaded, 200 indexed and 0 pending.

### Quick test without network

[`../../sample_dataset/raw/`](../../sample_dataset/raw/) holds the first 15 books of the dataset,
as raw files exactly as Gutenberg serves them. Starting from an empty `data/`:

```bash
python -m src.main.bigdata.main pipeline 15 --offline-source ../../sample_dataset/raw
python -m src.main.bigdata.main search whale island
```

```
[INFO] Found 3 books: [76, 84, 2701]
```

A later online `pipeline` finds those 15 books already indexed and downloads only the other 185.

## 4. Where the data goes

Everything is under `data/` (ignored by git):

```
data/
├── datalake/<structure>/                 raw books, the three structures at once
│   ├── time:   YYYYMMDD/HH/<id>.header.txt, <id>.body.txt
│   ├── book:   <id>/header.txt, body.txt
│   └── range:  01000-01999/<id>.header.txt, <id>.body.txt
├── datamarts/
│   ├── metadata.db                       SQLite, table books
│   ├── inverted_index.json               monolithic index: {"term": [ids...]}
│   └── inverted_index/<LETTER>/<term>.txt  hierarchical index: one id per line
└── control/
    ├── downloaded_books.txt              one id per line
    └── indexed_books.txt
```

The `mongo` index goes to the `inverted_index` collection of the `search_engine` database, one
document per term: `{"term": "whale", "postings": [ids...]}`.

**Resuming.** A book is marked as indexed only after its three indexes have been flushed (SPEC
section 8). If a run is stopped, the next `pipeline` continues with the first book not indexed;
saving or indexing a book twice adds nothing, so a book whose mark was never written is simply
processed again.

## 5. Code structure

Sources are in `src/main/bigdata/`, and `src/test/` has one test package per source package.

```
bigdata
├── main.py              CLI and pipeline loop: pipeline, search, status
├── models.py            RawBook, BookLocation, BookMetadata (frozen dataclasses)
├── crawler/             client.py (HTTP download), splitter.py (START/END split, offline read)
├── datalake/            base.py (Datalake, atomic writes), book_based.py, range_based.py, time_based.py
├── datamart/metadata/   parser.py (header regexes), repository.py (SQLite)
├── datamart/index/      tokenizer.py, base.py (InvertedIndex), monolithic.py, hierarchical.py,
│                        mongo.py, in_memory.py (reference for the benchmark checks)
├── control/             state_manager.py (ControlLayer: downloaded/indexed lists)
├── query/               search_service.py (AND search)
└── benchmark/           benchmark_runner.py (entry point), datalake_, index_, metadata_benchmark.py,
                         runner.py (timing, JavaRandom), books.py, csv_results.py, verify_results.py
```

How a book moves through `main.py`:

```
fetch_book / fetch_book_offline  ->  split_header_body
  -> BookBasedDatalake, TimeBasedDatalake, RangeBasedDatalake .save   then mark_as_downloaded
  -> parse_metadata -> MetadataRepository.save_all (SQLite)
  -> tokenize -> MonolithicJsonIndex, HierarchicalFolderIndex, MongoInvertedIndex .add_document
  -> flush the three indexes                                            then mark_as_indexed

search:  tokenize the query -> postings of each term -> intersection, shortest list first
```

Design decisions:

* **The three structures at once.** The pipeline writes every datalake and every index for each
  book, so one run fills all the structures that the benchmarks compare.
* **Atomic writes.** As in Java, datalake files, the monolithic JSON and each hierarchical term file
  are written to `<name>.tmp` and moved over `<name>` with `os.replace`. A crash never leaves a
  half-written file under its final name, and a `.tmp` never counts as a saved book.
* **Control files.** Ids are appended one per line. A last line left without `\n` by an interrupted
  append is ignored and trimmed when the control layer starts, so it is never glued to the next id.
* **The tokenizer is a loop, not a regex.** It walks the body character by character, as SPEC
  section 5 describes: `A-Z` is lowered to `a-z`, `a-z` and `0-9` extend the current token, and any
  other character (including every non-ASCII one) ends it. Tokens shorter than 2 characters and
  the stopwords of `shared/stopwords.txt` are dropped. This gives the same terms as Java and C++.
* **Index backends:**
  * `monolithic` keeps the whole index in memory and rewrites the JSON on every flush.
  * `hierarchical` keeps the pending ids in memory; a flush rewrites only the term files whose ids
    changed.
  * `mongo` sends all pending terms in one unordered `bulk_write` (upsert + `$addToSet`).
* **Java's random sequence where it matters.** `JavaRandom` (a port of `java.util.Random`) and
  `java_shuffle` (a port of `Collections.shuffle`) reproduce Java's sequence for the same seed. They
  choose the lookup order of `datalake_lookup` and the values of `metadata_query`, both with seed 42.

## 6. Benchmarks

The benchmarks implement the 12 experiments of SPEC sections 9 and 10. Each one runs 2 warm-up
repetitions that are discarded and 5 measured ones, timed with `time.perf_counter_ns`. Preparation
and cleanup happen outside the measured time. The datalake experiments check that every book is
found, that exactly the new books are detected and that recovery loses and duplicates nothing.
The index experiments check every backend against an in-memory reference index. A failed check
stops the run with a `RuntimeError` that names it.

| Module | Experiments | Compares |
|---|---|---|
| `datalake_benchmark.py` | `datalake_write`, `datalake_lookup`, `datalake_incremental`, `datalake_recovery`, `datalake_storage` | `book`, `range`, `time` |
| `index_benchmark.py` | `index_build`, `index_query`, `index_update`, `index_memory`, `index_disk` | `monolithic`, `hierarchical`, `mongo` |
| `metadata_benchmark.py` | `metadata_insert`, `metadata_query` | `sqlite`, `sqlite_no_index` (without the author/title indexes) |

`benchmark_runner` has two modes:

| Mode | Command | Data | Writes |
|---|---|---|---|
| real | `python -m src.main.bigdata.benchmark.benchmark_runner <book_datalake>` | the books of a `book` datalake, in ascending id order; index sizes 50, 100, 200 | 10 CSVs (datalake and index) in `benchmarks/results/real/` |
| synthetic | `python -m src.main.bigdata.benchmark.benchmark_runner` | 200 synthetic books (Zipf-distributed words), and 1,000 / 10,000 / 100,000 generated metadata rows (SPEC section 10.2) | 12 CSVs in `benchmarks/results/synthetic/`; the metadata experiments run only here |

The synthetic books use Python's `random`, so their datalake and index results are a Python-only
reference (SPEC section 10.3). The synthetic metadata rows use no random numbers and are compared
across languages. Mongo uses the `search_engine_bench` database, never the real index, and every
experiment empties its collection when it finishes. `benchmarks/data/` is scratch space, ignored
by git.

**Expected values.** With the 200 real books, these numbers are exact and identical in every
language:

| What | Value |
|---|---|
| `index_disk` terms / postings, N = 50 | 58,834 / 360,970 |
| N = 100 | 78,820 / 759,087 |
| N = 200 | 129,356 / 1,581,064 |
| `index_disk` bytes, N = 200 | monolithic 8,893,537; hierarchical 7,251,967 (129,356 files) |
| `datalake_storage` bytes (book, range, time) | 128,980,349 each |
| `datalake_storage` files / directories | 400 each / 200, 47, 21 |
| `datalake_storage` max entries per directory | 200, 220, 20 |
| `datalake_storage` allocated bytes | 130,883,584 / 130,256,896 / 130,150,400 |
| `datalake_recovery` recovered / lost / duplicates | 20 / 0 / 0 |
| `datalake_incremental` detected | 20 |

Mongo's `index_disk` bytes grow with N but are not exact: they are what WiredTiger reports.

`verify_results` checks the CSVs against these values and that every experiment has its 5 runs.
It prints one `OK`/`FAIL` line per check and `FAILURES: n`, and exits with code 1 if n > 0:

```bash
python -m src.main.bigdata.benchmark.verify_results               # real/ and synthetic/
python -m src.main.bigdata.benchmark.verify_results --real-only   # real/ only
```

**Results.** Each experiment writes `benchmarks/results/<mode>/python_<experiment>.csv` at the end
of its block (datalake, metadata, index), with a temporary file and a move:

```
language,experiment,structure,dataset_size,repetition,metric,value,unit
python,index_build,monolithic,200,1,elapsed,1234.5,ms
```

If `benchmarks/results/synthetic/` already holds its 12 CSVs, the synthetic run is skipped; add
`--force` to delete them and run it again. The real mode writes 10 CSVs, so it is never skipped.

### Running the benchmarks on Linux/macOS

The official results are produced with these steps, from this `python/stage_1/` folder.

**Input books.** The real mode reads the 200 books of `shared/book_ids.txt` from a `book` datalake.
Java's `book` datalake (`../../java/stage1/data/datalake/book`, left by `pipeline` in the Java
module) can be used directly: it is byte-for-byte identical to the one the Python pipeline writes,
because both follow the same split rules (SPEC section 2). If it does not exist, run the Python
pipeline first and use `data/datalake/book` instead.

**MongoDB** must be running (`docker compose up -d` from the repository root), or the `mongo`
backend is skipped.

```bash
# 1. Environment and tests
python3 -m venv venv
source venv/bin/activate
pip install -r ../requirements.txt pytest
pytest src/test

# 2. Input books: Java's book datalake, or the Python pipeline's
BOOKS=../../java/stage1/data/datalake/book
if [ ! -d "$BOOKS" ]; then
    python -m src.main.bigdata.main pipeline 200
    BOOKS=data/datalake/book
fi

# 3. Real mode first (datalake + index, 10 CSVs in benchmarks/results/real/)
python -m src.main.bigdata.benchmark.benchmark_runner "$BOOKS" > benchmarks/bench_real.log 2>&1

# 4. Then synthetic mode (12 CSVs in benchmarks/results/synthetic/; the metadata ones are only here)
python -m src.main.bigdata.benchmark.benchmark_runner > benchmarks/bench_synthetic.log 2>&1

# 5. Check the CSVs against the exact values above (exit code 1 if anything fails)
python -m src.main.bigdata.benchmark.verify_results
```

* Run the two modes one after the other, never at the same time: they share the
  `search_engine_bench` collection.
* Both logs must contain `[index] backends: ['monolithic', 'hierarchical', 'mongo']`.
* The CSVs are written only at the end of each block, so the machine must not sleep during the
  run, which takes hours.
* On Windows the same commands work (`venv\Scripts\activate`, and `>` for the logs), but the
  hierarchical index creates and deletes about 129,000 small files per repetition, which is much
  slower on NTFS with an antivirus scanning them. The official results were taken on Linux.

## 7. Good to know

* **What a step is.** `pipeline [steps]` counts *books*: each step downloads and indexes one book.
  In Java and C++ a step is one download *or* one indexing, so `pipeline 400` there equals
  `pipeline 200` here. "Pending" in `status` means not indexed yet.
* **Search uses the monolithic index.** The three indexes hold the same postings; `search` reads
  `inverted_index.json`.
* **Sample dataset in the benchmarks.** The 15 books of `../../sample_dataset/book/` use the `book`
  layout, so they can be passed to the real mode for a quick end-to-end check
  (`benchmark_runner ../../sample_dataset/book`); recovery then reports 2 / 0 / 0.
* **Starting over.** `rm -rf data` deletes the downloaded books, the indexes and the control files.
  It does not touch the Mongo collection; `docker compose down -v` at the repository root wipes it.
* **Quoting.** `search` joins all its arguments, so `search whale island` and
  `search "whale island"` are the same query.
