# Sample dataset

A small, fixed sample of the project's dataset: the group's original 15 books, in two forms. It
lets anyone try the pipeline and check an implementation without downloading hundreds of books.
The assignment requires it ("Provide a sample dataset so instructors can quickly test the
pipeline").

## Contents

| Path | What it is | Size |
|---|---|---|
| `raw/pg<ID>.txt` | The raw Project Gutenberg plain-text files, byte for byte as served by `https://www.gutenberg.org/cache/epub/<ID>/pg<ID>.txt` (SPEC section 2), downloaded on 2026-10-03. This is the **input** of the pipeline. | 7.7 MB |
| `book/<ID>/header.txt`, `book/<ID>/body.txt` | The same books after the header/body split of SPEC section 2, in the `book` datalake layout of SPEC section 3. This is the **expected output** of the split. | 6.3 MB |
| `book_ids.txt` | The 15 ids: the first 15 lines of `shared/book_ids.txt`, same order and format. | |
| `SHA256SUMS` | One checksum per file in `raw/` and `book/`. | |
| `.gitattributes` | Stops git from converting line endings. The raw files use CRLF, and any conversion would change their bytes. | |

| Id | Title | Author |
|---|---|---|
| 1342 | Pride and Prejudice | Jane Austen |
| 84 | Frankenstein; or, the modern prometheus | Mary Wollstonecraft Shelley |
| 11 | Alice's Adventures in Wonderland | Lewis Carroll |
| 1661 | The Adventures of Sherlock Holmes | Arthur Conan Doyle |
| 2701 | Moby Dick; Or, The Whale | Herman Melville |
| 98 | A Tale of Two Cities | Charles Dickens |
| 74 | The Adventures of Tom Sawyer, Complete | Mark Twain |
| 5 | The United States Constitution | United States |
| 1080 | A Modest Proposal | Jonathan Swift |
| 345 | Dracula | Bram Stoker |
| 2542 | A Doll's House : a play | Henrik Ibsen |
| 1952 | The Yellow Wallpaper | Charlotte Perkins Gilman |
| 46 | A Christmas Carol in Prose; Being a Ghost Story of Christmas | Charles Dickens |
| 174 | The Picture of Dorian Gray | Oscar Wilde |
| 76 | Adventures of Huckleberry Finn | Mark Twain |

## How to use it

- **Testing the whole pipeline without network:** use `raw/`. An offline run reads
  `raw/pg<ID>.txt` instead of the URL, for each id in `book_ids.txt`. Everything after the download
  (split, datalake, metadata, index, control files) stays exactly the same. The C++ module has this
  offline mode: `pipeline <N> --offline` (see `cpp/docs/USER_GUIDE.md`). Java and Python do not have it yet
  (2026-10-03).
- **Benchmarks, or any step that starts from books already split:** use `book/`. It is a ready-made
  `book` datalake. For example, Java's `DatalakeBenchmark` and `IndexBenchmark` accept a book
  datalake path such as `sample_dataset/book`.
- **Checking an implementation's header/body split:** split each file in `raw/` and compare the
  result with `book/<ID>/` byte for byte. They must be identical.

## Reference values

Processing these 15 books as SPEC sections 2 and 5 describe must give exactly **30,396 distinct
terms** and **89,727 postings**. Like the 200-book values in SPEC section 10.1, any difference
means a split or a tokenizer that does not follow the SPEC.

Verified on 2026-10-03. An independent script split every file in `raw/` into a header and body
byte-identical to what the C++ pipeline had stored for the same books. `book/` is that output. The
counts above were also obtained by the C++ pipeline and by its `index_disk` benchmark.

## Checking a copy

From this folder:

```bash
shasum -a 256 -c SHA256SUMS
```

On Linux, `sha256sum -c SHA256SUMS` does the same. Every line must end in `OK`.

## Rebuilding `raw/`

Project Gutenberg updates its files from time to time, so a new download may differ from this
snapshot. The checksums will show it, and the reference values may then change. From this folder:

```bash
for id in $(grep -v '^#' book_ids.txt); do curl -fsSL -o raw/pg$id.txt https://www.gutenberg.org/cache/epub/$id/pg$id.txt; sleep 1; done
```

## License

These books are in the public domain in the United States. Every file in `raw/` is kept
unmodified, including the Project Gutenberg License it carries; see
https://www.gutenberg.org/policy/license.html. The files in `book/` hold only the header and body
of each book, as SPEC section 2 defines them (the footer, which carries the license, is discarded).
