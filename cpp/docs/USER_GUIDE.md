# C++ module – user guide

How to build this module, run it from the terminal, and use its command-line interface (CLI).
The reasoning behind every design decision lives in [`DEVLOG.md`](DEVLOG.md), and a summary of the
whole module for readers of the project is in [`MODULE_REPORT.md`](MODULE_REPORT.md); the rules shared
with the Java and Python modules live in [`../../shared/SPEC.md`](../../shared/SPEC.md).

## 1. Requirements

Tested on macOS (Apple Silicon):

| Tool | Where it comes from |
|---|---|
| C++20 compiler (Apple Clang), SQLite3, libcurl | Xcode Command Line Tools: `xcode-select --install` |
| CMake ≥ 3.20, Ninja, mongo-cxx-driver | Homebrew: `brew install cmake ninja mongo-cxx-driver` |
| nlohmann/json, GoogleTest | Downloaded automatically by CMake on the first build |

A running MongoDB server is **not** required to build or test: without one, the Mongo-dependent tests
and benchmark rows are skipped automatically. To include the `mongo` structure, start the group's
MongoDB (the repository's `docker-compose.yml`, MongoDB 8.2.12) from the repository root. On a Mac
without Docker Desktop, Colima provides the Docker engine:

```bash
brew install colima docker docker-compose
colima start --cpu 2 --memory 4
docker-compose up -d
```

`docker-compose down` stops it again. The benchmarks write to their own database
(`search_engine_bench`) and the tests to `search_engine_test`, never to the real index's
`search_engine`.

## 2. Build and test

All commands below are run from the `cpp/` folder.

```bash
make
make test
```

`make` configures and builds in Release mode (needed for meaningful benchmark numbers). The first run
also downloads the dependencies. `make test` runs the whole GoogleTest suite (232 tests; the 11 Mongo
ones show as skipped without a MongoDB server, and all pass with one).

The result is one executable, `build/release/search_engine_stage1`. To save typing, store its path
in a shell variable for the rest of the session:

```bash
B=./build/release/search_engine_stage1
```

The binary finds `../shared/`, `../sample_dataset/` and its own `data/` folder through paths fixed
at build time, so it works from any directory.

## 3. Commands

| Command | What it does | Network | Writes to disk |
|---|---|---|---|
| `$B pipeline <N>` | Runs up to N pipeline steps over the 200 books of `shared/book_ids.txt`. Each step either **downloads** one book from Project Gutenberg (header/body split, datalake, metadata) or **indexes** one already-downloaded book, indexing first. Stops early when nothing is left. | yes | `data/` |
| `$B pipeline <N> --offline` | The same pipeline, but **without network**: the books are the 15 of `sample_dataset/`, read from `sample_dataset/raw/` instead of Project Gutenberg. Everything after fetching a book is identical. | no | `data/` |
| `$B search <words...>` | AND search: the books containing **every** word, with their titles. Words are tokenized like the books (case, punctuation and stopwords ignored). | no | nothing |
| `$B status` | How many books the dataset lists, how many are downloaded and indexed, and which are downloaded but not indexed yet. | no | nothing |
| `$B config` | The configuration file used and the structures `pipeline` and `search` work with (see below). | no | nothing |
| `$B benchmark <experiment>` | Runs one of the 12 SPEC experiments and writes its CSV under `benchmarks/results/` (see section 7). The datalake and index experiments use the books already downloaded; the metadata ones use synthetic rows at N=1,000, 10,000 and 100,000 (SPEC section 10.2). | no | `benchmarks/` |

Experiments: `datalake_write`, `datalake_lookup`, `datalake_incremental`, `datalake_recovery`,
`datalake_storage`, `metadata_insert`, `metadata_query`, `index_build`, `index_query`, `index_update`,
`index_memory`, `index_disk`.

Running `$B` with no arguments, or with wrong ones, prints this usage and exits with code 1.

### Choosing the structures

`pipeline` and `search` use one datalake structure and one inverted index, chosen in
[`config.properties`](../config.properties) (same keys and defaults as the Java module):

| Key | Values | Default |
|---|---|---|
| `datalake.structure` | `book`, `range`, `time` (SPEC section 3) | `time` |
| `index.structure` | `monolithic`, `hierarchical`, `mongo` (SPEC section 6) | `monolithic` |

To change one for a single run without editing the file, put `-Dkey=value` before the command:

```bash
$B -Dindex.structure=hierarchical pipeline 400
$B -Dindex.structure=hierarchical search whale island
$B -Ddatalake.structure=book config
```

- `pipeline` and `search` must use the same index: `search` reads the index it is told to, so if the
  pipeline built `hierarchical`, search with `hierarchical` too (or set it in the file).
- When the chosen index does not hold every indexed book (it does not exist yet, for example after
  switching from `monolithic` to `hierarchical`; or it was left partial or stale), the next
  `pipeline` run writes it whole first: about 13 s for the 200 books in `hierarchical`. It finds out
  by comparing the stored index's term count with the index rebuilt from the indexed books. After
  that, each indexed book only updates its own terms.
- `mongo` needs the group's MongoDB (section 1). Without a server, `pipeline` and `search` stop with
  `no MongoDB server reachable`.
- Each datalake structure has its own folder, `data/datalake/<structure>/`, so they can coexist.
  Books downloaded under one structure stay where they are: indexing reads each book through the
  path stored in the metadata.
- A typo stops the program before it does anything: an unknown key or value prints `[config] ...`
  and exits with code 1.
- The benchmarks do not use this configuration: they always compare every structure.

## 4. Quick start

**Without network, in about a second.** These are the 15 books of `sample_dataset/`, a snapshot
kept in the repository:

```bash
$B pipeline 30 --offline
$B status
$B search whale island
```

Each book takes one step to fetch and one to index, so 30 steps cover all 15. Then:

```
dataset:    200 book id(s) in shared/book_ids.txt
downloaded: 15
indexed:    15
pending:    0
```
```
3 book(s) matching all of: whale island
  76  Adventures of Huckleberry Finn
  84  Frankenstein; or, the modern prometheus
  2701  Moby Dick; Or, The Whale
```

**The whole dataset, from Project Gutenberg.** 200 books, 400 steps, about 10 minutes:

```bash
$B pipeline 400
```

Both modes write to the same `data/`. If you start offline and then run `pipeline` online, the 15
sample books are already done and only the other 185 are downloaded. The sample files are
byte-identical to what Gutenberg served when the sample was made, so the result is the same either
way.

## 5. Where the data goes

Everything `pipeline` produces lives under `data/`, which git ignores:

```
data/
├── datalake/<structure>/...                  datalake: each book as downloaded, split in two
│     time:  YYYYMMDD/HH/<id>.header.txt, <id>.body.txt
│     book:  <id>/header.txt, <id>/body.txt
│     range: 01000-01999/<id>.header.txt, <id>.body.txt
├── datamarts/metadata.db                     datamart: title, author, language... (SQLite)
├── datamarts/inverted_index.json             datamart, index.structure = monolithic
├── datamarts/inverted_index/<LETTER>/<term>.txt   datamart, index.structure = hierarchical
└── control/downloaded_books.txt              control layer: what is already done,
    control/indexed_books.txt                 so a new run resumes instead of repeating
```

With `index.structure = mongo`, the index is the `inverted_index` collection of the `search_engine`
database on the group's MongoDB instead.

You can check that the CLI tells the truth by reading these files directly:

```bash
cat data/control/indexed_books.txt
sqlite3 data/datamarts/metadata.db 'SELECT book_id, title, author FROM books;'
grep -o '"whale":\[[^]]*\]' data/datamarts/inverted_index.json
```

The ids in the last line must match the ones `$B search whale` prints.

## 6. Trying it by hand

**Resuming after an interruption.** This test starts from an empty `data/`, so move your current data
out of the way first. It runs offline, so it needs no network and takes seconds:

```bash
mv data ~/stage1_data_backup
$B pipeline 5 --offline
$B status
$B pipeline 40 --offline
```

| Step | Expected |
|---|---|
| `pipeline 5 --offline` | 3 books fetched, 2 indexed |
| `status` | `pending: 1 (downloaded, not indexed yet: 11)`, the third book |
| `pipeline 40 --offline` | after the `offline: reading books from ...` line, its **first** step is `indexed book 11`; it then continues and ends with `nothing left to do` |

The same works online (without `--offline`); it is just slower.

Pressing Ctrl+C in the middle of a `pipeline` run and starting it again behaves the same way: work
already recorded in `data/control/` is never repeated. Afterwards, either delete the backup
(`rm -rf ~/stage1_data_backup`) or restore it (`rm -rf data`, then `mv ~/stage1_data_backup data`).

**Edge cases and exit codes.** `echo $?` prints the exit code of the previous command:

```bash
$B search the and ; echo $?
$B search xyzzy ; echo $?
$B status x ; echo $?
```

| Command | Expected |
|---|---|
| `search the and` | `no searchable terms` (only stopwords), exit code 0 |
| `search xyzzy` | `0 book(s) matching all of: xyzzy`, exit code 0 |
| `status x` | usage message, exit code 1 |
| `search` before any `pipeline` run | `no monolithic index found -- run pipeline <N> first` (or the chosen structure), exit code 1 |
| `-Dindex.structure=memory status` | `[config] index.structure = "memory" does not exist. Options: ...`, exit code 1 |
| `pipeline <N>` when Project Gutenberg cannot be reached | `could not download book <ID>: <reason>`, then `stopping; run pipeline again to retry`, exit code 1. The book stays unmarked, so the next run retries it |

## 7. Good to know

- **Benchmark results and git.** Each experiment writes its CSV into a folder for its data (SPEC
  section 10.4) and its category, overwriting the previous run:

  ```
  benchmarks/results/
  ├── real/datalake/cpp_datalake_<write|lookup|incremental|recovery|storage>.csv   200 real books
  ├── real/index/cpp_index_<build|query|update|memory|disk>.csv                    N=50/100/200 real books
  └── synthetic/metadata/cpp_metadata_<insert|query>.csv                           N=1k/10k/100k generated rows
  ```

  These CSVs are tracked by git, and only runs with SPEC section 10's sizes are committed. If you
  only ran a benchmark to try it, restore the committed file with `git checkout -- <file>`, or
  delete it if git shows it as new. `benchmarks/work/` is scratch space, ignored by git.
- **Quoting in zsh.** Use single quotes for multi-word queries with punctuation, for example
  `$B search 'The Whale, and the Island!'`. Inside double quotes, zsh treats `!"` specially and
  leaves the quote open (a `dquote>` prompt; Ctrl+C gets you out).
- **Shortcut.** `make run ARGS='search whale'` builds and runs in one step. With no `ARGS` it runs
  `pipeline 5`.
- **Starting over.** `rm -rf data` deletes all downloaded books and the indexes. The next `pipeline`
  run downloads everything again.
- **Checking the offline output.** After `$B -Ddatalake.structure=book pipeline 30 --offline` on an
  empty `data/`, the datalake is byte-identical to the sample's expected output:
  `diff -r data/datalake/book ../sample_dataset/book` prints nothing.
