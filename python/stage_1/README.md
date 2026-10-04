# Python Module – User Guide

The Python implementation of the Stage 1 data layer. It downloads books from Project Gutenberg (or processes them locally via offline mode), splits each one into header and body, stores them in a datalake, extracts their metadata into SQLite, and builds an inverted index that answers AND searches. It also runs the comprehensive benchmarks that compare the storage structures.

The rules shared with the Java and C++ modules (split markers, folder layouts, tokenizer, index formats, CSV format) are in `../../shared/SPEC.md`.

## 1. Requirements

| Tool | Version | Notes |
| :--- | :--- | :--- |
| **Python** | 3.10 or newer | Tested with Python 3.10+ |
| **pip** | Latest | For installing dependencies |
| **MongoDB** | 6.0+ | Optional, only for the mongo index structure |

All required Python libraries are listed in `requirements.txt` (e.g., `requests` for downloading, `pymongo` for the NoSQL index). 

MongoDB is not strictly needed to run the basic pipeline or the local file tests. However, to execute the full benchmark suite (which includes the MongoDB inverted index), you must start the group's server from the repository root:

```bash
docker compose up -d

```

## 2. Setup and Test

All commands should be run from this `python/stage_1/` folder. First, set up your virtual environment and install dependencies:

```bash
python -m venv venv
# On Windows: venv\Scripts\activate
# On Linux/Mac: source venv/bin/activate

pip install -r requirements.txt

```

To run the unit tests, we use `pytest`. The test suite is structurally mirrored against the `src/main/bigdata` production code (e.g., `src/test/datalake`, `src/test/datamart`, etc.).

```bash
pytest src/test/

```

This discovers and runs all module-specific tests to ensure the integrity of the data layer.

## 3. Commands

The main entry point for the application is the `main.py` orchestrator.

| Command | What it does | Network |
| --- | --- | --- |
| `pipeline [steps]` | Processes up to *steps* new books. Each step downloads one book from Gutenberg, splits it, and indexes it. | Yes |
| `search <words...>` | AND search on the active index: returns books containing every word. | No |
| `status` | Displays the current count of downloaded, indexed, and pending (not yet indexed) books. | No |
| `--offline-source` | Skips Gutenberg network requests and loads books directly from a local folder. | No |

**Examples:**

```bash
# Run the pipeline for 200 books
python -m src.main.bigdata.main pipeline 200

# Search the index
python -m src.main.bigdata.main search pride prejudice

# Check the system status
python -m src.main.bigdata.main status

```

## 4. Where the data goes

Everything is stored under `data/` (which is ignored by Git to prevent polluting the repository):

```text
data/
├── datalake/<structure>/                 raw books, one folder per datalake structure
│   ├── time:   YYYYMMDD/HH/<id>.header.txt, <id>.body.txt
│   ├── book:   <id>/header.txt, body.txt
│   └── range:  01000-01999/<id>.header.txt, <id>.body.txt
├── datamarts/
│   ├── metadata.db                       SQLite, table books
│   ├── inverted_index.json               monolithic index
│   └── inverted_index/<LETTER>/<term>.txt  hierarchical index
└── control/
    ├── downloaded_books.txt              one id per line
    └── indexed_books.txt

```

The **MongoDB index** goes to the `inverted_index` collection of the `search_engine` database. Each term is stored as a document: `{"term": "whale", "postings": [ids...]}`.

## 5. Code Structure

Sources are inside `src/main/bigdata/`, and all unit tests faithfully mirror this architecture under `src/test/`.

* **`main.py`**: The entry point CLI.
* **`models.py`**: Shared data structures (RawBook, Metadata).
* **`crawler/`**: Handles HTTP downloads and marker-based splitting.
* **`datalake/`**: Implements the structural layouts (Time-based, Book-based, Range-based).
* **`datamart/`**: Contains the metadata (SQLite) and index (Monolithic JSON, Hierarchical folders, MongoDB) storage logics. Includes the custom Tokenizer.
* **`control/`**: State tracking to ensure no data is lost or duplicated upon interruption.
* **`benchmark/`**: The suite runner and timing engine for performance evaluation.

**Design Decisions & Python specifics:**

* **Tokenizer Parity**: The Python tokenizer uses no regex. It is a loop over the body, character by character, as SPEC section 5 describes: `A-Z` is lowered to `a-z`, `a-z` and `0-9` extend the current token, and any other character (including every non-ASCII one) ends it. Tokens shorter than 2 characters and the stopwords in `shared/stopwords.txt` are dropped. This guarantees the same terms as the Java and C++ implementations.
* **Seed Synchronization**: Python's native `random` module does not produce Java's sequence for the same seed. `JavaRandom` (a port of `java.util.Random`) and `java_shuffle` (a port of `Collections.shuffle`) reproduce it exactly, and are used where the comparison across languages depends on it: the lookup order of `datalake_lookup` and the values picked by `metadata_query`, both with seed 42. The synthetic *books* (`synthetic`, `synthetic_zipf`) still use Python's `random`, so, as SPEC section 10.3 says, their results are a per-language reference only and are not compared with Java or C++.
* **Atomic Writes**: As in Java, every file is first written to `<name>.tmp` and then moved over `<name>` with `os.replace`: the datalake headers and bodies, the monolithic `inverted_index.json` and each hierarchical term file. A crash halfway never leaves a half-written file under its final name; a leftover `.tmp` never counts as a saved book. The control files are append-only: a last line left without `\n` by an interrupted append is ignored and trimmed when the control layer starts.

## 6. Benchmarks

The Python benchmarking suite implements the 12 experiments defined in SPEC sections 9 and 10. The runner orchestrates operations across all datalake structures, metadata operations, and the three inverted index backends.

To run the full suite against the real books already downloaded in your datalake:

```bash
python -m src.main.bigdata.benchmark.benchmark_runner data/datalake/book

```

*Note: This process evaluates heavy disk I/O and MongoDB operations for thousands of terms and may take several hours to complete.*

**Results:**
The orchestrator generates 12 `.csv` files.

* `benchmarks/results/real/`: Contains the metrics for datalake and index experiments using the 200 authentic Project Gutenberg books.
* `benchmarks/results/synthetic/`: Contains the metadata experiments (which simulate up to 100,000 insertions) and virtual data tests.

## 7. Running the benchmarks on Linux/macOS

The official results are produced with these steps, from this `python/stage_1/` folder.

**Input books.** The real mode reads the 200 books of `shared/book_ids.txt` from a `book` datalake.
Java's `book` datalake (`../../java/stage1/data/datalake/book`, left by `pipeline` in the Java
module) can be used directly: it is byte-for-byte identical to the one the Python pipeline writes,
because both follow the same split rules (SPEC section 2). If it does not exist, run the Python
pipeline first and use `data/datalake/book` instead.

**MongoDB** must be running (`docker compose up -d` from the repository root), or the `mongo`
backend is skipped. The benchmarks only use the `search_engine_bench` database.

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

# 5. Check the CSVs against the exact values of SPEC section 10 (exit code 1 if anything fails)
python -m src.main.bigdata.benchmark.verify_results
```

* Run the two modes one after the other, never at the same time: they share the
  `search_engine_bench` collection.
* Both logs must contain `[index] backends: ['monolithic', 'hierarchical', 'mongo']`.
* Any internal check that fails stops the run with a `RuntimeError` that names it.
* The CSVs are written only at the end of each block (datalake, metadata, index), so the machine
  must not sleep during the run, which takes hours.
* If `benchmarks/results/synthetic/` already holds its 12 CSVs, the synthetic run is skipped; add
  `--force` to delete them and run it again. (The real mode writes 10 CSVs, so it is never
  skipped.)
* `verify_results --real-only` checks only `benchmarks/results/real/`.

## 8. Good to Know

* **Offline Mode (Python Exclusive)**: Unlike the Java module, this implementation fully supports local ingestion. You can feed the pipeline directly from a local folder without hitting Project Gutenberg limits:
```bash
python -m src.main.bigdata.main pipeline 15 --offline-source ../../sample_dataset/raw

```


* **Differences from Java/C++**: `pipeline [steps]` counts *books* (download and index are done together for each book), not separate actions; and `status` reports "Pending" as books that are *not indexed* yet.
* **Starting Over**: Running `rm -rf data` deletes downloaded books, indexes, and control files. It does *not* touch the MongoDB collection. To clear Mongo, run `docker compose down -v` at the repository root.
* **Benchmark Teardown**: The benchmark suite creates a temporary database (`search_engine_bench`) during its run. Every experiment empties its collection when it finishes, so nothing is left behind. Your production `search_engine` database remains untouched.

```