# C++ module – user guide

How to build this module, run it from the terminal, and use its command-line interface (CLI).
The reasoning behind every design decision lives in [`DEVLOG.md`](DEVLOG.md); the rules shared
with the Java and Python modules live in [`../shared/SPEC.md`](../shared/SPEC.md).

## 1. Requirements

Tested on macOS (Apple Silicon):

| Tool | Where it comes from |
|---|---|
| C++20 compiler (Apple Clang), SQLite3, libcurl | Xcode Command Line Tools: `xcode-select --install` |
| CMake ≥ 3.20, Ninja, mongo-cxx-driver | Homebrew: `brew install cmake ninja mongo-cxx-driver` |
| nlohmann/json, GoogleTest | Downloaded automatically by CMake on the first build |

A running MongoDB server is **not** required. Without one, the Mongo-dependent tests and benchmark
rows are skipped automatically.

## 2. Build and test

All commands below are run from this `cpp/` folder.

```bash
make
make test
```

`make` configures and builds in Release mode (needed for meaningful benchmark numbers). The first run
also downloads the dependencies. `make test` runs the whole GoogleTest suite (172 tests; the 3 Mongo
tests show as skipped without a MongoDB server).

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
| `$B benchmark <experiment>` | Runs one of the 12 SPEC experiments on the books already downloaded and writes `benchmarks/results/cpp_<experiment>.csv`. | no | `benchmarks/` |

Experiments: `datalake_write`, `datalake_lookup`, `datalake_incremental`, `datalake_recovery`,
`datalake_storage`, `metadata_insert`, `metadata_query`, `index_build`, `index_query`, `index_update`,
`index_memory`, `index_disk`.

Running `$B` with no arguments, or with wrong ones, prints this usage and exits with code 1.

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
├── datalake/book/<id>/header.txt, body.txt   datalake: each book as downloaded, split in two
├── datamarts/metadata.db                     datamart: title, author, language... (SQLite)
├── datamarts/inverted_index.json             datamart: word -> ids of the books containing it
└── control/downloaded_books.txt              control layer: what is already done,
    control/indexed_books.txt                 so a new run resumes instead of repeating
```

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
| `search` before any `pipeline` run | `no index found ... run pipeline <N> first`, exit code 1 |

## 7. Good to know

- **Benchmarks overwrite committed results.** `benchmarks/results/*.csv` are tracked by git. If you
  only ran a benchmark to try it, restore them with `git checkout -- benchmarks/results/`.
- **Quoting in zsh.** Use single quotes for multi-word queries with punctuation, for example
  `$B search 'The Whale, and the Island!'`. Inside double quotes, zsh treats `!"` specially and
  leaves the quote open (a `dquote>` prompt; Ctrl+C gets you out).
- **Shortcut.** `make run ARGS='search whale'` builds and runs in one step. With no `ARGS` it runs
  `pipeline 5`.
- **Starting over.** `rm -rf data` deletes all downloaded books and the indexes. The next `pipeline`
  run downloads everything again.
- **Checking the offline output.** After `pipeline 30 --offline` on an empty `data/`, the datalake is
  byte-identical to the sample's expected output: `diff -r data/datalake/book ../sample_dataset/book`
  prints nothing.
