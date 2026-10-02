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

The binary finds `../shared/` and its own `data/` folder through paths fixed at build time, so it
works from any directory.

## 3. Commands

| Command | What it does | Network | Writes to disk |
|---|---|---|---|
| `$B pipeline <N>` | Runs up to N pipeline steps. Each step either **downloads** one book from Project Gutenberg (header/body split, datalake, metadata) or **indexes** one already-downloaded book, indexing first. Stops early when nothing is left. | yes | `data/` |
| `$B search <words...>` | AND search: the books containing **every** word, with their titles. Words are tokenized like the books (case, punctuation and stopwords ignored). | no | nothing |
| `$B status` | How many books the dataset lists, how many are downloaded and indexed, and which are downloaded but not indexed yet. | no | nothing |
| `$B benchmark <experiment>` | Runs one of the 12 SPEC experiments on the books already downloaded and writes `benchmarks/results/cpp_<experiment>.csv`. | no | `benchmarks/` |

Experiments: `datalake_write`, `datalake_lookup`, `datalake_incremental`, `datalake_recovery`,
`datalake_storage`, `metadata_insert`, `metadata_query`, `index_build`, `index_query`, `index_update`,
`index_memory`, `index_disk`.

Running `$B` with no arguments, or with wrong ones, prints this usage and exits with code 1.

## 4. Quick start

```bash
$B pipeline 40
$B status
$B search whale island
```

`pipeline 40` downloads and indexes the 15 books in `shared/book_ids.txt` (about 20 seconds with a
normal connection; each book takes one download step and one index step). Then:

```
dataset:    15 book id(s) in shared/book_ids.txt
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

**Resuming after an interruption.** This test downloads everything again, so move your current data
out of the way first:

```bash
mv data ~/stage1_data_backup
$B pipeline 5
$B status
$B pipeline 40
```

| Step | Expected |
|---|---|
| `pipeline 5` | 3 books downloaded, 2 indexed |
| `status` | `pending: 1`, the third book (downloaded, not indexed yet) |
| `pipeline 40` | its **first** line indexes that pending book, then it continues and ends with `nothing left to do` |

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
