# Java module – user guide

The Java implementation of the Stage 1 data layer. It downloads books from Project Gutenberg,
splits each one into header and body, stores them in a datalake, extracts their metadata into
SQLite and builds an inverted index that answers AND searches. It also runs the benchmarks that
compare the storage structures.

The rules shared with the Python and C++ modules (split markers, folder layouts, tokenizer, index
formats, CSV format) are in [`../../shared/SPEC.md`](../../shared/SPEC.md). The design and the
benchmark results are discussed in the report,
[`docs/Stage1_Java_Report.pdf`](docs/Stage1_Java_Report.pdf). Its LaTeX source, and the script
that draws its charts from `benchmarks/results/`, are in [`docs/report/`](docs/report/).

## 1. Requirements

| Tool | Version |
|---|---|
| JDK | 17 or newer (tested with OpenJDK 21) |
| Maven | 3.x |
| MongoDB | optional, only for the `mongo` index structure |

Maven downloads the libraries: `sqlite-jdbc` (metadata), `jackson-databind` (monolithic JSON
index), `mongodb-driver-sync` (Mongo index) and JUnit 5 (tests).

MongoDB is not needed to build, test or run the module. Without a server, the Mongo tests are
skipped and `IndexBenchmark` measures the other two structures and prints a warning. To include it,
start the group's server from the repository root:

```bash
docker compose up -d
```

## 2. Build and test

All commands are run from this `java/stage1/` folder.

```bash
mvn package
```

This compiles the code and runs the 357 tests. The 12 MongoDB tests are skipped when no server is
reachable, and all of them pass with one. The tests need no network: Project Gutenberg is replaced
by a fake `BookSource`, or by the local files of `../../sample_dataset/` (`SampleDatasetTest`).

The `pom.xml` does not build an executable jar, so the program runs from the compiled classes plus
the library classpath. Save that classpath once:

```bash
mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
CP="target/classes:$(cat target/classpath.txt)"
```

`$CP` is used in every command below. Run `mvn compile` again after changing the code.

## 3. Commands

```bash
java -cp "$CP" es.ulpgc.bigdata.Main [--config file.properties] <command>
```

| Command | What it does | Network |
|---|---|---|
| `pipeline [steps]` | Runs up to `steps` pipeline steps (default 10). Each step does **one** thing: if a book is downloaded but not indexed, it indexes it; otherwise it downloads the next book of `shared/book_ids.txt`. Stops early when nothing is left. | yes |
| `pipeline [steps] --offline` | The same pipeline over the 15 books of `sample_dataset/book_ids.txt`, read from `sample_dataset/raw/` instead of Project Gutenberg. Everything after fetching a book is identical. See [Quick test without network](#quick-test-without-network). | no |
| `search <words...>` | AND search on the active index: the books that contain every word, with their titles. The query is tokenized like the books. | no |
| `status [--offline]` | Books in the dataset (the 15 of the sample with `--offline`), downloaded, indexed, and downloaded but still pending indexing. | no |
| `config` | The effective configuration, after applying the file, the environment and `-D` options. | no |

The program's messages are in Spanish (`libros para`, `descargados`, `pendientes`...).

### Example

```bash
java -cp "$CP" es.ulpgc.bigdata.Main pipeline 4
java -cp "$CP" es.ulpgc.bigdata.Main search pride prejudice
java -cp "$CP" es.ulpgc.bigdata.Main status
```

```
datalake=book  index=monolithic
DOWNLOADED 1342
INDEXED 1342
DOWNLOADED 84
INDEXED 84
```
```
2 libros para "pride prejudice" (index=monolithic)
  84  Frankenstein; or, the modern prometheus
  1342  Pride and Prejudice
```
```
dataset:     200
descargados: 2
indexados:   2
pendientes:  []
```

The whole dataset takes 400 steps (200 downloads and 200 indexings):

```bash
java -cp "$CP" es.ulpgc.bigdata.Main pipeline 400
```

### Quick test without network

[`../../sample_dataset/`](../../sample_dataset/) holds the first 15 books of the dataset, as raw
files exactly as Gutenberg serves them. With `--offline` the pipeline reads them instead of
downloading, so the whole module can be tried in about a second, with no network:

```bash
java -cp "$CP" es.ulpgc.bigdata.Main pipeline 30 --offline     # 15 downloads + 15 indexings
java -cp "$CP" es.ulpgc.bigdata.Main search whale island
java -cp "$CP" es.ulpgc.bigdata.Main status --offline
```

```
offline: libros leídos de .../sample_dataset/raw
datalake=book  index=monolithic
DOWNLOADED 1342
INDEXED 1342
...
```
```
3 libros para "whale island" (index=monolithic)
  76  Adventures of Huckleberry Finn
  84  Frankenstein; or, the modern prometheus
  2701  Moby Dick; Or, The Whale
```
```
dataset:     15 (sample_dataset)
descargados: 15
indexados:   15
pendientes:  []
```

The `-D` options work as usual, so every structure can be tried offline, for example
`-Ddatalake.structure=range -Dindex.structure=hierarchical`. Use `-Ddata.dir=/tmp/sample` to keep
the sample run apart from `data/`. The data goes to the same `data/` as an online run otherwise; the
raw files are byte-identical to what Gutenberg serves, so a later online `pipeline` finds those 15
books already done and downloads only the other 185.

`SampleDatasetTest` (run by `mvn package`) does the same thing and also checks what
`sample_dataset/README.md` promises: the split of every raw file is byte-identical to
`sample_dataset/book/<ID>/`, and the index has exactly 30,396 distinct terms and 89,727 postings.

### Step results

Each line of `pipeline` is one step:

| Result | Meaning | Marked in the control layer |
|---|---|---|
| `DOWNLOADED <id>` | Downloaded, split and saved in the datalake | `downloaded_books.txt` |
| `INDEXED <id>` | Metadata saved and index flushed | `indexed_books.txt` |
| `NOT_AVAILABLE <id>` | Gutenberg has no such book, or it lacks the START/END markers | no |
| `DOWNLOAD_FAILED <id>` | Network or disk error while downloading | no |
| `INDEX_FAILED <id>` | Error while indexing | no |
| `MISSING_FROM_DATALAKE <id>` | The control layer says "downloaded" but the active datalake does not have the book | no |

A book that fails is skipped for the rest of the run and retried in the next one. When fewer steps
than requested were done, the run ends with `IDLE: no queda nada por descargar ni indexar`
("nothing left to download or index").

### Without Maven's classpath step

`mvn exec:java` also works, at the cost of starting Maven each time:

```bash
mvn -q compile exec:java -Dexec.mainClass=es.ulpgc.bigdata.Main -Dexec.args="search whale island"
```

Run the benchmarks with plain `java`, not this way: `index_memory` measures the heap of the JVM it
runs in, and Maven's own objects would be counted too.

## 4. Configuration

The settings are in [`config.properties`](config.properties), which is read when it exists in the
folder the program is started from. Relative paths are resolved from that folder.

| Key | Default | Values |
|---|---|---|
| `data.dir` | `data` | Where the datalake, datamarts and control files go |
| `shared.dir` | `../../shared` | Where `book_ids.txt`, `stopwords.txt` and `queries.txt` are |
| `sample.dir` | `../../sample_dataset` | The sample dataset used by `--offline` (`book_ids.txt` and `raw/`) |
| `benchmarks.dir` | `benchmarks` | Benchmark results (`results/`) and scratch folders (`work/`) |
| `datalake.structure` | `book` | `book`, `range` or `time` |
| `index.structure` | `monolithic` | `monolithic`, `hierarchical`, `mongo`, or `memory` (not persisted; for tests) |
| `mongo.uri` | `mongodb://localhost:27017` | Any MongoDB connection string |
| `mongo.database` | `search_engine` | |
| `mongo.collection` | `inverted_index` | |
| `http.connect.timeout.seconds` | `10` | Gutenberg connection timeout |
| `http.request.timeout.seconds` | `15` | Gutenberg request timeout |

`book` and `monolithic` are the most efficient structures in the benchmarks (fastest write, lookup,
index build, query and update with the 200 real books), so `config.properties` selects them; the
reasons are in section 3.1 of the [report](docs/Stage1_Java_Report.pdf). Without a
`config.properties`, `AppConfig` falls back to `time`.

Later sources override earlier ones:

1. the defaults in `AppConfig`;
2. `config.properties`, or the file given with `--config`;
3. the `MONGO_URI` environment variable, for `mongo.uri`;
4. Java system properties, `-Dkey=value`.

So the structures can be changed without editing any file:

```bash
java -cp "$CP" -Ddatalake.structure=book -Dindex.structure=hierarchical es.ulpgc.bigdata.Main pipeline 400
```

An unknown key or an invalid value stops the program at startup with an error that lists the
valid options.

## 5. Where the data goes

Everything is under `data.dir` (`data/`, ignored by git):

```
data/
├── datalake/<structure>/                 raw books, one folder per datalake structure
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

You can check the CLI against the files:

```bash
cat data/control/indexed_books.txt
sqlite3 data/datamarts/metadata.db 'SELECT book_id, title, author FROM books;'
grep -o '"whale":\[[^]]*\]' data/datamarts/inverted_index.json
```

**Changing structures on existing data.** Each datalake structure has its own folder, but the
control files and the metadata are shared:

* With a different `datalake.structure`, the books already marked as downloaded are not in the new
  folder. Any pending indexing then fails with `MISSING_FROM_DATALAKE`, and those books are not
  downloaded again.
* With a different `index.structure`, the books already marked as indexed are not added to the new
  index.

To try another combination, give it its own folder (`-Ddata.dir=data-book-hierarchical`) or start
over with `rm -rf data`.

**Resuming.** A mark is written only after its step has finished: the book is in the datalake, or
the index has been flushed. If a run is stopped (Ctrl+C, a network failure), the next `pipeline`
continues where it stopped without repeating or losing books. Saving and indexing the same book
twice is harmless, so a book whose mark was never written is simply processed again.

## 6. Code structure

Sources are in `src/main/java/es/ulpgc/bigdata/`, and each package has its tests in the same
package under `src/test/java/`.

```
es.ulpgc.bigdata
├── Main                      CLI: parses the command, prints the result, no logic
├── SearchEngine              wires all the pieces from an AppConfig (the only place that does)
├── config/                   AppConfig, DatalakeFactory, InvertedIndexFactory
├── crawler/                  BookSource, GutenbergClient, BookSplitter, BookDownloader
├── datalake/                 Datalake, AbstractFileDatalake, Book/Range/TimeBasedDatalake
├── datamart/metadata/        MetadataParser, MetadataRepository, SqliteMetadataRepository, SqliteSchema
├── datamart/index/           Tokenizer, Indexer, InvertedIndex + 4 implementations
├── control/                  ControlFiles, BookIdList, PipelineController, StepResult
├── query/                    SearchService
├── model/                    RawBook, BookMetadata, BookLocation
└── benchmark/                DatalakeBenchmark, IndexBenchmark, MetadataBenchmark + support
```

How a book moves through the system:

```
PipelineController.step()
 ├─ download:  BookDownloader = GutenbergClient (fetch) -> BookSplitter (split) -> Datalake (save)
 │             then ControlFiles.markDownloaded
 └─ index:     Indexer = Datalake (read) -> MetadataParser -> MetadataRepository (SQLite)
                                         -> Tokenizer      -> InvertedIndex (add + flush)
               then ControlFiles.markIndexed

search:        SearchService = Tokenizer -> InvertedIndex.postings per term -> intersection
```

Design decisions:

* **Interfaces for every storage choice.** The rest of the system only uses `Datalake`,
  `InvertedIndex`, `MetadataRepository` and `BookSource`. The factories turn a configuration name
  into an implementation, so adding a structure means one class and one line in a factory.
* **All paths come from `AppConfig`.** No other class builds a path such as `data/...`.
* **Atomic writes.** Datalake files, the monolithic JSON and each hierarchical term file are
  written to a temporary file and then moved, so a crash never leaves a half-written file that
  counts as valid.
* **The tokenizer works on ASCII only** (SPEC section 5). It does not use `toLowerCase()` or
  `Character.isLetter()`, because their Unicode rules cannot be reproduced byte by byte in C++,
  and the three languages have to produce the same terms.
* **Index backends:**
  * `monolithic` keeps the whole index in memory and rewrites the JSON on every flush.
  * `hierarchical` writes only the term files that changed since the last flush.
  * `mongo` sends all pending terms in one unordered `bulkWrite` (upsert + `$addToSet`).
* **Hierarchical folder names.** Terms that start with a digit go to the folder of that digit,
  for example `1/1813.txt`.

## 7. Benchmarks

The benchmarks implement the 12 experiments of SPEC sections 9 and 10. Each one runs 2 warm-up
repetitions that are discarded and 5 measured ones. Preparation and cleanup happen outside the
measured time. After measuring, the index benchmark checks that every backend returns the same
results as an in-memory reference index, and fails otherwise.

| Class | Experiments | Compares |
|---|---|---|
| `DatalakeBenchmark` | `datalake_write`, `datalake_lookup`, `datalake_incremental`, `datalake_recovery`, `datalake_storage` | `book`, `range`, `time` |
| `IndexBenchmark` | `index_build`, `index_query`, `index_update`, `index_memory`, `index_disk` | `monolithic`, `hierarchical`, `mongo` |
| `MetadataBenchmark` | `metadata_insert`, `metadata_query` | `sqlite`, `sqlite_no_index` (without the author/title indexes) |

The benchmarks never use the network. They read the real books from a `book` datalake that the
pipeline has already filled, so download the dataset into one first:

```bash
java -cp "$CP" -Ddatalake.structure=book es.ulpgc.bigdata.Main pipeline 400
```

Then, with the sizes of SPEC section 10:

```bash
java -cp "$CP" es.ulpgc.bigdata.benchmark.DatalakeBenchmark data/datalake/book
java -cp "$CP" es.ulpgc.bigdata.benchmark.IndexBenchmark 50,100,200 data/datalake/book
java -cp "$CP" es.ulpgc.bigdata.benchmark.MetadataBenchmark 1000,10000,100000
```

| Benchmark | Arguments | Without arguments |
|---|---|---|
| `DatalakeBenchmark` | `[book_datalake]` | 200 synthetic books of 300 KB |
| `IndexBenchmark` | `[sizes] [book_datalake]` | sizes `50,100,200` on synthetic books (Zipf-distributed words) |
| `MetadataBenchmark` | `[sizes]` | `1000,10000,100000` generated rows (SPEC section 10.2) |

A size N is always the N books with the lowest ids, as in the other languages. With the real books,
`index_disk` must report 58,834 / 78,820 / 129,356 distinct terms for N = 50 / 100 / 200. Any other
number means the books or the tokenizer differ from the other implementations. `IndexBenchmark`
writes to the `search_engine_bench` database, never to the real index. With MongoDB, the full run
takes about 10 minutes.

**Results.** Each experiment writes `benchmarks/results/java_<experiment>.csv`, all at once, so a
failed run leaves the previous file untouched:

```
language,experiment,structure,dataset_size,repetition,metric,value,unit
java,index_build,monolithic,100,1,elapsed,1234.5,ms
```

The committed runs are archived by hand into the folders of SPEC section 10.4:

* [`benchmarks/results/real/`](benchmarks/results/real/): datalake and index experiments on the
  200 real books.
* [`benchmarks/results/synthetic/`](benchmarks/results/synthetic/): the metadata experiments, plus
  the datalake and index experiments on synthetic books. The synthetic books depend on Java's random
  number generator, so these are a Java-only reference and are not compared with other languages.

`benchmarks/work/` is scratch space, ignored by git.

## 8. Good to know

* **Sample dataset in the benchmarks.** The 15 books of `../../sample_dataset/book/` use the `book`
  layout, so they can also be passed to the benchmarks (`DatalakeBenchmark ../../sample_dataset/book`).
* **Starting over.** `rm -rf data` deletes the downloaded books, the indexes and the control files.
  It does not touch the Mongo collection; `docker compose down -v` at the repository root wipes it.
* **Quoting.** `search` joins all its arguments, so `search whale island` and
  `search "whale island"` are the same query.
