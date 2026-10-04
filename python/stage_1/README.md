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

* **Tokenizer Parity**: The Python tokenizer strictly uses regex `[a-zA-Z0-9]+` and outputs lowercase ASCII to guarantee 100% term parity with the Java and C++ implementations.
* **Seed Synchronization**: Python's native `random` module does not match Java's output. To ensure our synthetic benchmarks process the exact same virtual data as our teammates, we implemented a custom `JavaRandom(42)` generator.
* **Atomic Writes**: Local files (JSON and hierarchical text) are written carefully to avoid corruption during unexpected shutdowns.

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

## 7. Good to Know

* **Offline Mode (Python Exclusive)**: Unlike the Java module, this implementation fully supports local ingestion. You can feed the pipeline directly from a local folder without hitting Project Gutenberg limits:
```bash
python -m src.main.bigdata.main pipeline 15 --offline-source ../../sample_dataset/raw

```


* **Differences from Java/C++**: `pipeline [steps]` counts *books* (download and index are done together for each book), not separate actions; and `status` reports "Pending" as books that are *not indexed* yet.
* **Starting Over**: Running `rm -rf data` deletes downloaded books, indexes, and control files. It does *not* touch the MongoDB collection. To clear Mongo, run `docker compose down -v` at the repository root.
* **Benchmark Teardown**: The benchmark suite creates a temporary database (`search_engine_bench`) during its run. Every experiment empties its collection when it finishes, so nothing is left behind. Your production `search_engine` database remains untouched.

```