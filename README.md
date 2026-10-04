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

```text
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

```text
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
| Language | JDK 17+ | Python 3.10+ | C++20 compiler (GCC or Clang) |
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
mvn package                     # compile and run the 357 tests (the Mongo ones are skipped without a server)
mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
CP="target/classes:$(cat target/classpath.txt)"
```

Then run the command-line interface with `java -cp "$CP" es.ulpgc.bigdata.Main <command>`:

| Command | What it does |
|---|---|
| `pipeline [steps]` | Runs up to `steps` steps (default 10). Each step either downloads one book (split, datalake, metadata) or indexes one downloaded book. Indexing goes first. |
| `pipeline [steps] --offline` | The same pipeline, reading the 15 books of `sample_dataset/raw/` instead of the network |
| `search <words...>` | AND search on the active index. Prints the ids and titles. |
| `status [--offline]` | Books in the dataset (the sample's 15 with `--offline`), downloaded, indexed and still pending. |
| `config` | The effective configuration. |

```bash
java -cp "$CP" es.ulpgc.bigdata.Main pipeline 400    # the whole dataset: 200 downloads + 200 indexings
java -cp "$CP" es.ulpgc.bigdata.Main search whale island
java -cp "$CP" es.ulpgc.bigdata.Main status
```

Quick try, with no network, in about a second:

```bash
java -cp "$CP" es.ulpgc.bigdata.Main pipeline 30 --offline
java -cp "$CP" es.ulpgc.bigdata.Main search whale island      # 76, 84 and 2701
```

The configuration is in [`java/stage1/config.properties`](java/stage1/config.properties). Any key
can be overridden with `-Dkey=value`, so the structures change without touching code:

```bash
java -cp "$CP" -Ddatalake.structure=book -Dindex.structure=hierarchical es.ulpgc.bigdata.Main pipeline 400
```

* `datalake.structure`: `book` (selected in `config.properties`), `range` or `time`
* `index.structure`: `monolithic` (selected in `config.properties`), `hierarchical`, `mongo` or `memory`

`book` and `monolithic` are the most efficient structures in the Java benchmarks; the report
explains the choice.
* The `MONGO_URI` environment variable overrides `mongo.uri`.

`mvn -q exec:java -Dexec.mainClass=es.ulpgc.bigdata.Main -Dexec.args="status"` also works, without
the classpath step. The benchmarks should still be run with plain `java`, because the memory
experiments measure the heap of the JVM they run in.

The Java module has its own detailed guide in [`java/stage1/README.md`](java/stage1/README.md)
(configuration, where data goes, code structure, benchmarks). The design and the benchmark results
are discussed in the Java report,
[`java/stage1/docs/Stage1_Java_Report.pdf`](java/stage1/docs/Stage1_Java_Report.pdf).

### 🐍 Python (`python/stage_1/`)

```bash
cd python/stage_1
python -m venv venv
source venv/bin/activate          # Windows: venv\Scripts\activate
pip install -r ../requirements.txt pytest
pytest src/test                   # 28 tests
```

Then run the command-line interface with `python -m src.main.bigdata.main <command>`:

| Command | What it does |
|---|---|
| `pipeline [steps]` | Processes up to `steps` books of `shared/book_ids.txt` (all of them by default). For each book not indexed yet it downloads it, writes it to **the three datalake structures at once**, stores its metadata in SQLite and adds it to **the three indexes at once** (MongoDB only if a server is reachable). Each book is flushed before it is marked as indexed, so an interrupted run resumes where it stopped. |
| `pipeline [steps] --offline-source <dir>` | The same pipeline, reading `pg<ID>.txt` files from a local folder instead of the network |
| `search <words...>` | AND search on the monolithic index. Prints the ids. |
| `status` | Books in the dataset, downloaded, indexed and pending (not indexed yet). |

```bash
python -m src.main.bigdata.main pipeline 200      # the whole dataset
python -m src.main.bigdata.main search whale island
python -m src.main.bigdata.main status
```

Quick try, with no network:

```bash
python -m src.main.bigdata.main pipeline 15 --offline-source ../../sample_dataset/raw
python -m src.main.bigdata.main search whale island      # 76, 84 and 2701
```

The data goes to `python/stage_1/data/`. The Python module has its own guide in
[`python/stage_1/README.md`](python/stage_1/README.md) (where data goes, code structure,
benchmarks). The design and the benchmark results are discussed in the Python report,
[`python/stage_1/docs/Stage1_Python_Report.pdf`](python/stage_1/docs/Stage1_Python_Report.pdf)
(LaTeX sources in [`python/stage_1/docs/report/`](python/stage_1/docs/report/)).

### ⚙️ C++ (`cpp/`)

```bash
cd cpp
make                 # Release build; the first run also downloads nlohmann/json and GoogleTest
make test            # GoogleTest suite: 232 tests, the 11 Mongo ones skipped without a server
B=./build/release/search_engine_stage1
```

| Command | What it does |
|---|---|
| `$B pipeline <N>` | Up to N steps over the 200 books, downloading or indexing one book per step, as in Java |
| `$B pipeline <N> --offline` | The same pipeline, reading the 15 books of `sample_dataset/raw/` instead of the network |
| `$B search <words...>` | AND search, with the titles |
| `$B status` | Downloaded, indexed and pending books |
| `$B config` | The configuration file used and the active structures |
| `$B benchmark <experiment>` | Runs one of the 12 benchmark experiments (see below) |

The pipeline's datalake and index are chosen in [`cpp/config.properties`](cpp/config.properties),
with the same keys as Java:

* `datalake.structure`: `book` (selected in `config.properties`), `range` or `time`
* `index.structure`: `monolithic` (selected in `config.properties`), `hierarchical` or `mongo`

`book` and `monolithic` are also the most efficient structures in the C++ benchmarks, the same choice
as Java's; section 3 of the [C++ report](cpp/docs/Stage1_Cpp_Report.pdf) explains it. Any key can be changed
for one run with `-Dkey=value` before the command. `pipeline` and `search` must use the same index:

```bash
$B -Dindex.structure=hierarchical pipeline 400
$B -Dindex.structure=hierarchical search whale island
```

Quick try, with no network, in about a second:

```bash
$B pipeline 30 --offline
$B search whale island      # 76, 84 and 2701
```

The C++ module's documentation is in [`cpp/docs/`](cpp/docs/): a user guide,
[`USER_GUIDE.md`](cpp/docs/USER_GUIDE.md) (where data goes, resuming after an interruption, exit codes); a
technical report, [`Stage1_Cpp_Report.pdf`](cpp/docs/Stage1_Cpp_Report.pdf) (LaTeX sources in
[`cpp/docs/report/`](cpp/docs/report/)); and the in-depth development log,
[`DEVLOG.md`](cpp/docs/DEVLOG.md).

## Sample dataset

[`sample_dataset/`](sample_dataset/) holds the first 15 books of `shared/book_ids.txt` in two forms:

* `raw/pg<ID>.txt`: the files exactly as Project Gutenberg served them, the **input** of the pipeline.
* `book/<ID>/header.txt` and `body.txt`: the same books after the split, the **expected output**.

It lets instructors test the pipeline without downloading the full dataset (`pipeline --offline`
in Java and C++, `--offline-source` in Python), and lets any implementation check its splitter
against the expected files. See its [README](sample_dataset/README.md).

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

**Python** (from `python/stage_1/`, after `pipeline 200`). Two runs, one after the other: the
real mode writes the 10 datalake and index CSVs to `benchmarks/results/real/`, and the synthetic mode
writes 12 CSVs, the metadata ones included, to `benchmarks/results/synthetic/`. `verify_results`
then checks the CSVs against the exact values of SPEC section 10:

```bash
python -m src.main.bigdata.benchmark.benchmark_runner data/datalake/book > benchmarks/bench_real.log 2>&1
python -m src.main.bigdata.benchmark.benchmark_runner > benchmarks/bench_synthetic.log 2>&1
python -m src.main.bigdata.benchmark.verify_results
```

### Committed results

| Implementation | Folder |
|---|---|
| Java | [`java/stage1/benchmarks/results/`](java/stage1/benchmarks/results/): `real/` (datalake and index, 10 CSVs) and `synthetic/` (12 CSVs, metadata included) |
| C++ | [`cpp/benchmarks/results/`](cpp/benchmarks/results/): `real/datalake/`, `real/index/` and `synthetic/metadata/` |
| Python | [`python/stage_1/benchmarks/results/`](python/stage_1/benchmarks/results/): `real/` (datalake and index, 10 CSVs) and `synthetic/` (12 CSVs, metadata included), measured on Linux |

Only results from runs with the sizes of SPEC section 10 are committed. The comparison of the
implementations and storage structures is in the report submitted on the virtual campus.

## Implementation status

The three implementations are at different points. As of 2026-10-04:

| | Java | Python | C++ |
|---|---|---|---|
| Pipeline (crawler, datalake, metadata, index, control) | ✅ | ✅ | ✅ |
| Datalake structure | one, chosen in the configuration | the three at once | one, chosen in the configuration |
| Index structure | one, chosen in the configuration | the three at once | one, chosen in the configuration |
| Search command | ✅ | ✅ | ✅ |
| Offline mode (`sample_dataset/raw/`) | ✅ | ✅ (`--offline-source`) | ✅ |
| Benchmarks in the SPEC CSV format | ✅ all 12 | ✅ all 12 (run on Linux) | ✅ all 12 |
| Tests | 357, all passing | 28, all passing | 232, all passing |
