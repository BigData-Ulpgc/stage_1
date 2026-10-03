# Search Engine Project – Stage 1: Building the Data Layer

Big Data course project (ULPGC). The goal of the whole project is a search engine over books from
[Project Gutenberg](https://www.gutenberg.org/). **Stage 1** builds its **data layer**: the pipeline
that downloads the books, cleans them, stores them, extracts their metadata and builds an inverted
index that can answer simple searches.

The same data layer is implemented **three times, in Java, Python and C++**, against one common
contract ([`shared/SPEC.md`](shared/SPEC.md)). All three read the same dataset and follow the same
preprocessing rules, so they produce the same outputs. Several storage structures are benchmarked
in each language, with the same methodology everywhere.

## Team

* **Members:**
  * Daniel Rodriguez Alonso – Java implementation
  * Daniel Perdomo Medina – Python implementation
  * Carlos Falcón Brito – C++ implementation

## Architecture

```
shared/book_ids.txt
       │
       ▼
┌────────────┐   HTTP GET https://www.gutenberg.org/cache/epub/<ID>/pg<ID>.txt
│  Crawler   │   split at the "*** START/END OF THE PROJECT GUTENBERG EBOOK" markers
└─────┬──────┘   (a book without both markers is discarded)
      │ header + body
      ▼
┌────────────┐   raw books, one header file and one body file per book
│  Datalake  │   structures compared: time (YYYYMMDD/HH/), book (<ID>/), range (01000-01999/)
└─────┬──────┘
      │
      ├──────────────────────────────┐
      ▼                              ▼
┌────────────────────┐    ┌───────────────────────────┐
│ Metadata datamart  │    │ Inverted-index datamart   │
│ SQLite: title,     │    │ term -> sorted book ids   │
│ author, language,  │    │ monolithic JSON file,     │
│ release date, paths│    │ hierarchical folders,     │
└────────────────────┘    │ or MongoDB                │
                          └─────────────┬─────────────┘
                                        ▼
                               AND query: intersection of the posting lists

Control layer: data/control/downloaded_books.txt and indexed_books.txt
               (an id is appended only after its step succeeded, so an interrupted
                run resumes without losing or repeating books)
```

| Layer | Contents | SPEC section |
|---|---|---|
| **Crawler** | Downloads a book and splits it into header and body, dropping the license footer | §2 |
| **Datalake** | Raw books, in one of three folder layouts (`time`, `book`, `range`) | §3 |
| **Metadata datamart** | SQLite table `books` with indexes on `author` and `title` | §4 |
| **Tokenizer** | ASCII letters and digits only, lowercase, tokens of 2+ characters, stopwords removed, one set of terms per book | §5 |
| **Inverted-index datamart** | `monolithic` (one JSON file), `hierarchical` (`<LETTER>/<term>.txt`), `mongo` (one document per term) | §6 |
| **Query** | The query is tokenized like a book, then AND semantics: the books containing every term | §7 |
| **Control layer** | Lists of downloaded and indexed ids | §8 |

## Repository layout

```
.
├── shared/              the common contract and inputs, read by the three implementations
│   ├── SPEC.md          rules all implementations follow (in Spanish, sections 1-9; English, section 10)
│   ├── book_ids.txt     the 200 Gutenberg ids of the dataset
│   ├── stopwords.txt    stopwords used by the tokenizer
│   └── queries.txt      query workload of the index_query benchmark
├── sample_dataset/      the first 15 books, raw and already split, for quick tests without network
├── docker-compose.yml   the MongoDB 8.2 server shared by the three implementations
├── java/stage1/         Java 17 + Maven implementation
├── python/              Python implementation
└── cpp/                 C++20 + CMake implementation
```

Every implementation keeps the data it generates (datalake, datamarts, control files) in a `data/`
folder, and git ignores it.

## Requirements

| | Java | Python | C++ |
|---|---|---|---|
| Language | JDK 17+ | Python 3.9+ | C++20 compiler (GCC or Clang) |
| Build | Maven 3 | `pip` | CMake ≥ 3.20, Ninja, Make |
| Libraries | Downloaded by Maven (sqlite-jdbc, Jackson, MongoDB driver, JUnit 5) | `requests`, `pymongo` | SQLite3, libcurl, mongo-cxx-driver (system); nlohmann/json and GoogleTest are downloaded by CMake |

MongoDB is **optional** for all three. It is only needed for the `mongo` index structure, and
every implementation skips it (with a warning) when no server is reachable. To use it, start the
group's shared server from the repository root, with Docker:

```bash
docker compose up -d      # MongoDB on 127.0.0.1:27017
docker compose down       # stop it, keeping the data
docker compose down -v    # stop it and wipe the data (before a clean index_build benchmark)
```

The real index lives in the `search_engine` database. Benchmarks use `search_engine_bench` and the
tests `search_engine_test`, so they never touch the real index.

## Running each module

Each module's commands are run from its own folder. Paths in this section are relative to the
repository root.

### ☕ Java (`java/stage1/`)

```bash
cd java/stage1
mvn package                     # compile and run the 354 tests (the Mongo ones are skipped without a server)
mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
CP="target/classes:$(cat target/classpath.txt)"
```

Then run the command-line interface with `java -cp "$CP" es.ulpgc.bigdata.Main <command>`:

| Command | What it does |
|---|---|
| `pipeline [steps]` | Runs up to `steps` steps (default 10). Each step either downloads one book (split, datalake, metadata) or indexes one downloaded book. Indexing goes first. |
| `search <words...>` | AND search on the active index. Prints the ids and titles. |
| `status` | Books in the dataset, downloaded, indexed and still pending. |
| `config` | The effective configuration. |

```bash
java -cp "$CP" es.ulpgc.bigdata.Main pipeline 400    # the whole dataset: 200 downloads + 200 indexings
java -cp "$CP" es.ulpgc.bigdata.Main search whale island
java -cp "$CP" es.ulpgc.bigdata.Main status
```

The configuration is in [`java/stage1/config.properties`](java/stage1/config.properties). Any key
can be overridden with `-Dkey=value`, so the structures change without touching code:

```bash
java -cp "$CP" -Ddatalake.structure=book -Dindex.structure=hierarchical es.ulpgc.bigdata.Main pipeline 400
```

* `datalake.structure`: `time` (default), `book` or `range`
* `index.structure`: `monolithic` (default), `hierarchical`, `mongo` or `memory`
* The `MONGO_URI` environment variable overrides `mongo.uri`.

`mvn -q exec:java -Dexec.mainClass=es.ulpgc.bigdata.Main -Dexec.args="status"` also works, without
the classpath step. The benchmarks should still be run with plain `java`, because the memory
experiments measure the heap of the JVM they run in.

The Java module has its own detailed guide in [`java/stage1/README.md`](java/stage1/README.md)
(configuration, where data goes, code structure, benchmarks). The design and the benchmark results
are discussed in the Java report,
[`java/stage1/docs/Memoria_Stage1_Java.pdf`](java/stage1/docs/Memoria_Stage1_Java.pdf).

### 🐍 Python (`python/`)

```bash
cd python
pip install -r requirements.txt
cd stage_1/src/main/bigdata
python main.py
```

`main.py` goes through all the ids of `shared/book_ids.txt` in a single run. For each book that is
not indexed yet, it downloads the book, writes it to **the three datalake structures at once**,
stores its metadata in SQLite and adds it to **the three indexes at once** (MongoDB only if a
server is reachable). Books already in the control layer are skipped, so running it again only
processes what is left. The output goes to `data/` at the repository root, except the control
files (see [Implementation status](#implementation-status)).

After a run, `python test_indices.py` (from the same folder) prints the size of each index and a
few sample lookups.

Unit tests (splitter, range layout, metadata parser, tokenizer), from `python/stage_1/`:

```bash
python -m unittest discover -s src/test -t .
```

### ⚙️ C++ (`cpp/`)

```bash
cd cpp
make                 # Release build; the first run also downloads nlohmann/json and GoogleTest
make test            # GoogleTest suite: 217 tests, the 10 Mongo ones skipped without a server
B=./build/release/search_engine_stage1
```

| Command | What it does |
|---|---|
| `$B pipeline <N>` | Up to N steps over the 200 books, downloading or indexing one book per step, as in Java |
| `$B pipeline <N> --offline` | The same pipeline, reading the 15 books of `sample_dataset/raw/` instead of the network |
| `$B search <words...>` | AND search, with the titles |
| `$B status` | Downloaded, indexed and pending books |
| `$B benchmark <experiment>` | Runs one of the 12 benchmark experiments (see below) |

Quick try, with no network, in about a second:

```bash
$B pipeline 30 --offline
$B search whale island      # 76, 84 and 2701
```

The C++ module has its own detailed guide in [`cpp/README.md`](cpp/README.md) (where data goes,
resuming after an interruption, exit codes) and a development log in
[`cpp/DEVLOG.md`](cpp/DEVLOG.md).

## Sample dataset

[`sample_dataset/`](sample_dataset/) holds the first 15 books of `shared/book_ids.txt` in two forms:

* `raw/pg<ID>.txt`: the files exactly as Project Gutenberg served them, the **input** of the pipeline.
* `book/<ID>/header.txt` and `body.txt`: the same books after the split, the **expected output**.

It lets instructors test the pipeline without downloading the full dataset (`pipeline --offline`
in C++), and lets any implementation check its splitter against the expected files. See its
[README](sample_dataset/README.md).

## Benchmarks

The SPEC (sections 9 and 10) fixes the experiments, the data and the methodology, so the three
languages can be compared:

* **2 warm-up repetitions** are discarded and **5 measured runs** are kept.
* There is **no network inside a measurement**. The real books are downloaded once by the pipeline
  into a `book` datalake, and the benchmarks read them from there.
* A size N means **the N books with the lowest ids**, so every language measures the same books.
  As a check, the index must have exactly 58,834 / 78,820 / 129,356 distinct terms for N = 50 /
  100 / 200.
* Every result is a CSV row: `language,experiment,structure,dataset_size,repetition,metric,value,unit`.

| Experiments | Compares | Data |
|---|---|---|
| `datalake_write`, `datalake_lookup`, `datalake_incremental`, `datalake_recovery`, `datalake_storage` | `time`, `book`, `range` | 200 real books |
| `index_build`, `index_query`, `index_update`, `index_memory`, `index_disk` | `monolithic`, `hierarchical`, `mongo` | 50, 100, 200 real books |
| `metadata_insert`, `metadata_query` | SQLite with and without the `author`/`title` indexes | 1,000 / 10,000 / 100,000 generated rows |

### Running them

**Java** (from `java/stage1/`, with `$CP` as above). Download the books into a `book` datalake
first. The results are written to `benchmarks/results/`:

```bash
java -cp "$CP" -Ddatalake.structure=book es.ulpgc.bigdata.Main pipeline 400
java -cp "$CP" es.ulpgc.bigdata.benchmark.DatalakeBenchmark data/datalake/book
java -cp "$CP" es.ulpgc.bigdata.benchmark.IndexBenchmark 50,100,200 data/datalake/book
java -cp "$CP" es.ulpgc.bigdata.benchmark.MetadataBenchmark 1000,10000,100000
```

Without the datalake argument, `DatalakeBenchmark` and `IndexBenchmark` use synthetic books instead.

**C++** (from `cpp/`, after `$B pipeline 400`). One experiment per call; each one writes its CSV
straight into `benchmarks/results/real/` or `synthetic/`:

```bash
$B benchmark datalake_write
$B benchmark index_build
$B benchmark metadata_query
```

**Python** (from `python/stage_1/src/main/bigdata/`):

```bash
python benchmark/benchmark_datalake.py    # write time of the 3 datalake structures
python benchmark/benchmark_index.py       # build time of the 3 indexes
```

These two scripts download their books first (outside the timed part) and write
`data/benchmarks/datalake_benchmark.csv` and `index_benchmark.csv` at the repository root, in their
own format.

### Committed results

| Implementation | Folder |
|---|---|
| Java | [`java/stage1/benchmarks/results/`](java/stage1/benchmarks/results/): `real/` (datalake and index, 10 CSVs) and `synthetic/` (12 CSVs, metadata included) |
| C++ | [`cpp/benchmarks/results/`](cpp/benchmarks/results/): `real/datalake/`, `real/index/` and `synthetic/metadata/` |
| Python | none committed yet |

Only results from runs with the sizes of SPEC section 10 are committed. The comparison of the
implementations and storage structures is in the report submitted on the virtual campus.

## Implementation status

The three implementations are at different points. As of 2026-10-03:

| | Java | Python | C++ |
|---|---|---|---|
| Pipeline (crawler, datalake, metadata, index, control) | ✅ | ✅ | ✅ |
| Datalake structure | one, chosen in the configuration | the three at once | `book` |
| Index structure | one, chosen in the configuration | the three at once | `monolithic` for the pipeline, the three in the benchmarks |
| Search command | ✅ | ❌ (`test_indices.py` only prints lookups) | ✅ |
| Offline mode (`sample_dataset/raw/`) | ❌ | ❌ | ✅ |
| Benchmarks in the SPEC CSV format | ✅ all 12 | ❌ 2 scripts, own format | ✅ all 12 |
| Tests | 354, all passing | 17, all passing | 217, one failing on Linux (below) |

Known issues, found while preparing this README:

* **Python tokenizer:** `tokenize` splits with `\w+`, which also keeps `_` and non-ASCII letters
  (`é`, `ñ`...). SPEC section 5 keeps only `a-z` and `0-9`, so the Python index will not match the
  reference term counts above. The docstring already describes `[a-zA-Z0-9]+`, the correct pattern.
* **Python paths:** the datalake, the datamarts and the benchmark CSVs go to `data/` at the
  repository root. `ControlLayer`, however, defaults to `../data/control` relative to the working
  directory, which ends up in `python/stage_1/src/main/data/control/` when run as shown above.
* **Python control layer:** the monolithic index is saved only once, at the end of the run, but each
  book is marked as indexed as soon as it is processed. If the run is interrupted, those books stay
  marked as indexed but are missing from `inverted_index.json`.
* **C++ test on Linux:** `BenchmarkIndexMemory.ReportsHeapAfterBuildAndAfterOpenPerStructureLikeTheJavaModule`
  expects the hierarchical index to use less memory than the monolithic one after reopening. With
  glibc it measures 256 vs 224, so the test fails. The module was developed on macOS, where it
  passes. The other 216 tests pass on Linux.
