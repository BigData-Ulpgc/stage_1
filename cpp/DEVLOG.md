# C++ module – development log

This log records **what** was done in the C++ implementation and, above all, **why**
each decision was taken (and which alternatives were discarded). It feeds sections 3
(architecture) and 4 (design decisions) of the final report. New entries go at the
bottom; never rewrite history, add a correction entry instead.

Conventions for the whole module:
- All code, comments, commit messages and this log are written in **English**.
- The shared contract in `../shared/SPEC.md` is the source of truth for behaviour
  (tokenizer, datalake layouts, index formats, CSV benchmark format).

---

## Entry 1 – Build environment (2026-09-20)

### What was done
Created the `cpp/` module skeleton:

| File | Role |
|------|------|
| `CMakeLists.txt` | Defines the project, fetches dependencies, declares the executable and tests |
| `CMakePresets.json` | Two named configurations: `release` (benchmarks) and `debug` (development) |
| `Makefile` | Thin shortcuts (`make`, `make test`, `make run`, `make clean`) that call CMake |
| `.clangd` | Points the IDE to `build/release/compile_commands.json` |
| `src/main.cpp` | Placeholder executable that prints a ready message |
| `tests/` | GoogleTest executable with smoke tests (JSON, SQLite, libcurl) |

Also extended the root `.gitignore` with C++/CMake artifacts and the runtime `cpp/data/` folder.

### Why these decisions

**C++ (and not plain C).** The team README assigns this part to "C", but the module is
written in C++ by request of its author. To stay compatible with `SPEC.md` (which was
designed so the tokenizer can be implemented byte by byte), we only rely on the
byte-oriented subset of C++ (`std::string`, `char`) and avoid Unicode libraries.
*Open point:* confirm with the group that C++ is an acceptable third language.

**CMake + `FetchContent` instead of Conan, vcpkg or a hand-written Makefile.**
- Conan and vcpkg are not installed and would force every teammate (and the professor)
  to install a package manager first.
- A hand-written Makefile cannot download or version dependencies.
- `FetchContent` downloads pinned versions into `build/` on first configure: no global
  installs, reproducible, and works with a single `make`.

**Dependency choices.**
- *GoogleTest 1.17.0* – de-facto standard C++ test framework (the C++ analogue of the
  JUnit used in the Java module).
- *nlohmann/json 3.12.0* – needed for the monolithic index `inverted_index.json`.
  Version 3.12.0 is required because older releases declare a CMake minimum version
  that CMake 4.x rejects.
- *SQLite3* – metadata store required by the spec. Taken from the system because it
  ships with macOS and is trivial to install on Linux (`libsqlite3-dev`).
- *libcurl* – HTTP client to download books from Project Gutenberg. Also from the system.
- *MongoDB driver* – **deferred**. It is heavy and the assignment lists it as one option
  among several index structures. Revisit if the group decides to benchmark it.

**Release by default.** The benchmarks (stage requirement) must measure optimized code;
a Debug build would distort the language comparison. A `debug` preset exists for development.

**C++20 with `-Wall -Wextra -Wpedantic`.** Modern standard library features
(`std::filesystem`, `std::string_view`, ranges) and strict warnings to catch mistakes early.

**Umbrella `stage1_deps` INTERFACE target.** Executable and tests link one target instead
of repeating the dependency list; adding a dependency later is a one-line change.

**Smoke tests.** Before writing any real logic we prove that each dependency compiles,
links and runs. If a later test fails, we know the environment is not the culprit.

**`cpp/data/` in `.gitignore`.** Datalake, datamarts and control files are generated at
runtime and can be huge; only the small `sample_dataset/` requested by the assignment
should be committed. *Assumption:* the data root is `cpp/data/`; align with the Java
module if it uses a different one.

### Result
`make test` configures, builds and passes the 3 smoke tests; `make run` prints the ready message.

---

## Entry 2 – Conventions and SQLite target fix (2026-09-20)

### What was done
- Translated every comment and string written in Entry 1 from Spanish to English
  (`CMakeLists.txt`, `tests/`, `src/main.cpp`, `Makefile`, `CMakePresets.json`, `.gitignore` block).
- Renamed the smoke test suite from `Entorno` to `Environment`.
- Fixed a CMake warning: the imported target `SQLite::SQLite3` is deprecated on recent CMake (observed with 4.4.3)
  in favour of `SQLite3::SQLite3`. `CMakeLists.txt` now picks whichever exists.
- Created this log (`cpp/DEVLOG.md`).

### Why
- **English everywhere:** agreed project convention (see the conventions block at the top).
  Doing it now, while the code base is tiny, avoids a large translation later.
- **Conditional target name instead of just the new one:** the declared minimum is CMake 3.20,
  and teammates or the professor may have an older CMake that only knows `SQLite::SQLite3`.
  Using only the new name would break them; keeping only the old name prints a warning on new CMake.
- **Append-only log:** the fix is a new entry rather than an edit of Entry 1, so the history
  of decisions stays honest and usable as report material.

---

## Entry 3 – Tokenizer core (2026-09-20)

### What was done
- `include/stage1/tokenizer.hpp` + `src/tokenizer.cpp`: `std::vector<std::string> stage1::tokenize(std::string_view)`.
  Lowercases `A-Z`, keeps `a-z0-9`, treats every other byte as a separator, drops tokens shorter than 2.
- `tests/tokenizer_test.cpp`: 5 tests (punctuation/apostrophes, non-ASCII bytes, empty input,
  last token without trailing separator, minimum length).
- New static library target `stage1_core` (all project logic). The executable and the test
  binary link against it instead of compiling sources twice.
- Stopword filtering is intentionally **not** included yet (next step).

### Why
- **Manual byte comparison instead of `std::tolower` / `std::isalnum`.** Those functions depend on
  the C locale and are undefined for negative `char` values (UTF-8 bytes are negative in a signed
  `char`). SPEC section 5 requires results identical to Java and Python, so the rule is spelled out
  explicitly: only ASCII letters and digits count.
- **`std::string_view` as input.** The tokenizer only reads the text; a view avoids copying a whole
  book (often several MB) just to read it.
- **Pure function, no I/O, no global state.** Easy to test, reusable by the indexer and by the query
  engine (which must tokenize queries with the exact same rules).
- **`stage1_core` library.** Keeps `main` thin and lets tests exercise the real code.
- **Tests before moving on.** The two cases from the theory exercise (`Adventure's`, `café`) are
  encoded as tests because they are the classic places where implementations diverge.
- **Known limitation (by design):** accented words are split (`café` -> `caf`). This mirrors the
  shared spec; changing it requires agreement of all three languages.

---

## Entry 4 – Editor setup for IntelliSense (2026-09-20)

### What was done
- Diagnosed a VS Code error (`C/C++(1696)`: cannot open `stage1/tokenizer.hpp`). It is an editor
  problem only: the compiler and all tests were already fine.
- Local fix in the (git-ignored) `.vscode/settings.json`: `C_Cpp.default.compileCommands` now points to
  `cpp/build/release/compile_commands.json`, and `C_Cpp.default.includePath` lists `cpp/include` as a fallback.

### Why
- The Microsoft C/C++ extension does not read `cpp/.clangd` (that file is only for the *clangd* extension),
  and with CMake presets the compilation database lives in `build/release/`, not in `build/`.
- The `includePath` fallback keeps headers resolvable even when the CMake Tools extension has no preset
  selected and therefore supplies an empty configuration.
- Not committed on purpose: `.vscode/` is ignored, so each teammate keeps their own editor settings.
  If a teammate sees the same error: select the `release` preset in CMake Tools, run
  "C/C++: Reset IntelliSense Database" and reload the window. Opening the `cpp/` folder directly also avoids it.
- Status: the compilation database was verified to contain `-I.../cpp/include`; the editor-side result was
  confirmed by the author.

---

## Entry 5 – Stopword loading (2026-09-20)

### What was done
- `include/stage1/stopwords.hpp` + `src/stopwords.cpp`:
  `std::unordered_set<std::string> stage1::load_stopwords(const std::filesystem::path&)`.
  Reads one word per line, ignores empty lines and lines starting with `#`, trims whitespace
  (including a trailing `\r`), and throws `std::runtime_error` if the file cannot be opened.
- `tests/stopwords_test.cpp`: 3 tests (comments/blank lines/CRLF, missing file, the real
  `shared/stopwords.txt`).
- `tests/CMakeLists.txt` defines `STAGE1_SHARED_DIR` so tests can read the shared contract files.
- The tokenizer does **not** use the stopwords yet: that is the next step (2b).

### Why
- **`std::unordered_set` instead of a `vector`.** The tokenizer will ask "is this token a stopword?"
  for every token of every book (millions of times). A hash set answers in O(1) on average;
  scanning a vector would be O(number of stopwords) per token.
- **Loading is separated from filtering.** File I/O and the tokenizing rules are different
  responsibilities; the tokenizer stays a pure function that receives the set as a parameter,
  which keeps it trivially testable and lets the benchmark load the list only once.
- **Throw on a missing file instead of returning an empty set.** An empty set would silently keep
  every stopword in the index and produce results different from Java and Python, with no visible
  error. Failing loudly is safer for a comparison that must yield equivalent outputs.
- **Tolerate `\r`, blank lines and comments** because SPEC section 1 says blank lines and `#`
  lines are ignored in all shared files, and a file edited on Windows would carry `\r\n`.
- **No lowercasing of the loaded words.** The SPEC states the file is already lowercase and the
  tokenizer output is lowercase, so extra normalization would hide a broken file instead of exposing it.
- **Test against the real shared file** so that a future edit of `shared/stopwords.txt` that breaks
  the format is caught by the C++ tests as well.

---

## Entry 6 – Stopword filtering inside the tokenizer (2026-09-20)

### What was done
- Added the overload `tokenize(std::string_view, const std::unordered_set<std::string>& stopwords)`.
  The original one-argument `tokenize(text)` now delegates to it with an empty set.
- The filter lives in the existing `flush` lambda: a finished token is kept only if it is at least
  2 characters long **and** is not in the stopword set.
- 4 new tests: removal (including the theory example `the car is nice` -> `car`, `nice`),
  matching after lowercasing, empty set = no filtering, and whole-token matching (`theory` survives `the`).
- SPEC section 5 (tokenizer) is now fully implemented; the "set of terms per book" rule (step 6) is left
  for the inverted-index phase.

### Why
- **Filter at token close time (`flush`), not as a second pass over the vector.** One traversal, no
  temporary vector of unwanted tokens, and the single place that decides "keep or drop" stays in one spot.
- **Overload + delegation instead of a default argument or a global stopword list.** The stopword set is
  passed in explicitly, so there is no hidden global state; the query engine and the indexer will call
  the same function with the same set, which guarantees identical rules on both sides (SPEC section 7).
  The one-argument version keeps existing callers and tests unchanged.
- **Reference to a `const` set, not a copy.** The set is loaded once and shared by every book; copying
  it per call would cost far more than tokenizing a short text.
- **Order of checks (length first, then hash lookup).** The cheap comparison runs first and `&&`
  short-circuits, so one-character tokens never pay for a hash computation.
- **Whole-token comparison only.** Stopwords are matched against complete tokens, never as substrings,
  which is what "remove stopwords" means and what Java/Python do.

---

## Entry 7 – Header/body split (2026-09-20)

### What was done
- `include/stage1/book_splitter.hpp` + `src/book_splitter.cpp`:
  `std::optional<SplitBook> stage1::split_book(std::string_view raw_text)` implements SPEC section 2
  (CRLF normalization, THE/THIS markers, header = before START, body = from the end of the START line
  to the END marker, footer discarded, `nullopt` if a marker is missing).
- `include/stage1/text_utils.hpp`: `stage1::trim`, extracted from `stopwords.cpp` because a second module
  now needs it. `load_stopwords` was refactored to use it (behaviour unchanged, its tests still pass).
- `tests/book_splitter_test.cpp`: 7 tests (normal split, THIS variant, CRLF, missing START, missing END,
  END before START, empty body). Suite total: 22 tests.
- Deliberately **not** done yet: downloading with libcurl and writing to the datalake. This step works
  on an in-memory string only.

### Why
- **Pure function over a string, not tied to the network.** Splitting can be tested with tiny hand-made
  books, and later the benchmarks can feed already-downloaded books (SPEC section 9 requires
  measuring writes without network noise).
- **`std::optional` instead of an exception or a bool + out-parameters.** A book without markers is an
  expected situation (SPEC: "the book is discarded"), not an error; `optional` forces the caller to handle it.
- **END marker searched only after the START line.** Otherwise an END text appearing earlier (or inside
  the header) would produce a negative-length body. There is a test for it.
- **Body starts after the START line, not right after the marker text.** SPEC section 2 says so; the rest of
  that line is the book title and would pollute the index. (The Python example in the course PDF splits
  right after the marker, so the shared SPEC is stricter than the PDF: all three languages must follow the SPEC.)
- **`trim` returns a `string_view`.** No allocation for the intermediate result; the only copies are the two
  final strings that the `SplitBook` owns.
- **`trim` extracted rather than duplicated.** Two copies of the whitespace rule could drift apart and
  silently make headers/stopwords differ.
- **Open point for the group:** "trim" differs slightly between languages (Java `trim()` strips all
  characters <= U+0020, Python `strip()` strips Unicode whitespace, this C++ version strips
  space, \t, \r, \n, \f, \v). For Gutenberg texts the difference should not show up, but it is worth
  comparing the outputs of the three implementations on the sample dataset.

---

## Entry 8 – Download result type (2026-09-25)

### What was done
- `include/stage1/download_result.hpp`: header-only `stage1::DownloadResult`, a small "either" type
  that holds the downloaded text on success or an error message on failure, never both. Built through
  named factories `success(text)` / `failure(message)`; `ok()` says which case it is; `text()`/`error()`
  throw `std::logic_error` if called on the wrong case.
- `tests/download_result_test.cpp`: 4 tests (success path, failure path, misuse of `text()` on a
  failure, misuse of `error()` on a success). Suite total: 26 tests.
- No libcurl code yet: this is only the vocabulary the download function (next step) will return.

### Why
- **A dedicated result type instead of throwing on every network error.** A failed HTTP download
  (a 404, a timeout, no network) is an expected, frequent outcome when crawling thousands of books,
  not a programming bug; the control layer (SPEC section 8) must be able to see it and move on to the
  next book instead of unwinding the stack. `std::optional`, used for `split_book`, was not enough
  here because on failure we also want to keep *why* it failed, not just the absence of a value.
- **Private constructor + named factories (`success`/`failure`) instead of a public two-field struct.**
  A public `{bool ok; std::string value;}` lets calling code build an inconsistent object by mistake
  (e.g. `ok = true` with an error string in `value`) and gives no name to which meaning `value` has.
  The factories make every construction site self-describing and keep the invariant "success carries
  text XOR failure carries a message" impossible to violate from outside the class.
- **Throwing on misuse (`text()` on a failure) rather than returning an empty string.** An empty string
  returned silently would be indistinguishable from "downloaded an empty file" and would hide a bug in
  the caller (forgetting to check `ok()` first); failing loudly matches the same reasoning already used
  for `load_stopwords` on a missing file.
- **Header-only.** The type has no state beyond two members and no file or network access, so there is
  nothing to put in a `.cpp`; consistent with `text_utils.hpp`.

---

## Entry 9 – Downloading books with libcurl (2026-09-27)

### What was done
- `include/stage1/gutenberg_client.hpp` + `src/gutenberg_client.cpp`:
  - `book_download_url(book_id)` — pure function, builds the SPEC section 2 URL, no network.
  - `http_get(url)` — performs one HTTP GET with libcurl and returns a `DownloadResult`.
  - `download_book(book_id)` — convenience: `http_get(book_download_url(book_id))`.
  - Internal `CurlHandle`: a small RAII wrapper around the library's `CURL*` handle.
  - Internal `write_callback`: the function libcurl calls with each chunk of the response body.
- `tests/gutenberg_client_test.cpp`: 2 URL-construction tests (no network) and 1 test that
  downloads book 1342 for real and checks its title appears in the body; it `GTEST_SKIP`s
  instead of failing if the download itself fails, since that would be an environment
  limitation, not a bug in our code. All 3 passed here, including the real download (1.91s).
  Suite total: 29 tests.

### Why
- **Split into three functions instead of one big `download_book`.** `book_download_url` needs no
  network and no mocking to test, so the URL format (an exact match to SPEC section 2) is checked on
  every run. `http_get` is reusable if later stages need to GET something that is not a Gutenberg book.
- **`CurlHandle` (RAII) instead of calling `curl_easy_init`/`curl_easy_cleanup` by hand inside
  `http_get`.** A `CURL*` is an opaque pointer the library hands us — the same idea as the `sqlite3*`
  from the environment smoke test — and it must be released exactly once. Wrapping it in a class means
  the handle is freed automatically when the function returns, on every path, including if a
  `curl_easy_setopt` call were to throw; copying is deleted so two `CurlHandle`s can never fight over
  freeing the same pointer.
- **`write_callback` as a free function, not a lambda with captures.** libcurl is a C library: it calls
  the callback through a plain function pointer and has no notion of a C++ capture. The place to pass
  "our" data is the separate `void*` in `CURLOPT_WRITEDATA`, which we set to the address of the
  `std::string` we want filled, and `static_cast` it back to `std::string*` inside the callback.
- **Returning `DownloadResult` (Entry 8) instead of throwing on every failure.** A 404 or a network
  timeout is an expected, frequent event when crawling thousands of books; the caller decides whether
  that is fatal or just "skip this book" without paying for exception handling on the common path.
- **Checking the HTTP status code, not only `curl_easy_perform`'s return value.** `curl_easy_perform`
  reports transport-level problems (DNS failure, connection refused, timeout); a 404 page is
  transported successfully, so it must be checked separately via `CURLINFO_RESPONSE_CODE`. Without
  this check a missing book would silently "succeed" with an HTML error page as its body.
- **`CURLOPT_FOLLOWLOCATION` enabled.** Gutenberg can redirect between `http`/`https` or mirror hosts;
  without following redirects those cases would surface as an unexpected non-2xx status.
- **No explicit `curl_global_init`.** libcurl performs that setup automatically on the first
  `curl_easy_init` when the program is single-threaded, which matches this stage's pipeline; if a later
  stage introduces concurrent downloads, an explicit `curl_global_init(CURL_GLOBAL_DEFAULT)` once at
  `main` start becomes necessary (libcurl's own global init is not thread-safe to call implicitly from
  multiple threads at once).
- **The real-network test skips instead of failing on a download error.** Whether the grading machine
  or a teammate's machine has outbound internet is outside our code's control; failing the whole suite
  for that reason would be misleading. The two URL-format tests still run unconditionally and catch a
  real regression in `book_download_url`.

---

## Entry 10 – Generalizing the client and the book source (2026-09-27)

### What was done
Refactor on top of Entry 9, requested by the user so any future stage can swap the HTTP
transport or add a second book provider without touching existing code:
- `include/stage1/http_client.hpp`: abstract `HttpClient` with one pure virtual method,
  `get(url) -> DownloadResult`. Contract for "fetch whatever is at this URL".
- `include/stage1/curl_http_client.hpp` + `src/curl_http_client.cpp`: `CurlHttpClient`, the only
  `HttpClient` implementation so far. Holds the `CurlHandle` RAII wrapper and `write_callback`
  moved unchanged from `gutenberg_client.cpp` (Entry 9); logic itself did not change.
- `include/stage1/book_source.hpp`: abstract `BookSource` with one pure virtual method,
  `fetch(book_id) -> DownloadResult`. Contract for "get a book's raw text, from wherever it lives".
- `include/stage1/gutenberg_client.hpp` / `src/gutenberg_client.cpp`: kept `book_download_url`
  unchanged; replaced the old free functions `http_get`/`download_book` with `GutenbergSource`,
  a `BookSource` that takes an `HttpClient&` in its constructor (dependency injection) instead of
  creating its own `CurlHttpClient`.
- `tests/fakes/fake_http_client.hpp`: `FakeHttpClient`, a test-only `HttpClient` that returns a
  canned `DownloadResult` and records every URL it was asked for. Test-only code, not part of
  `stage1_core`.
- `tests/gutenberg_client_test.cpp` rewritten: the 2 URL tests are unchanged; 2 new tests use
  `FakeHttpClient` to check `GutenbergSource` builds the right URL and propagates both success and
  failure, with no network involved; the real-network test now goes through `CurlHttpClient` +
  `GutenbergSource` together. Suite total: 31 tests, including the real download (1.55s here).

### Why
- **Two separate interfaces, not one.** `HttpClient` answers "how do I speak HTTP" (reusable for any
  URL, any future stage); `BookSource` answers "how do I get book N's text" (reusable across book
  providers, each of which will use an `HttpClient` underneath). Collapsing them into one interface
  would force every new book provider to also reimplement HTTP transport, even though that part never
  changes.
- **`GutenbergSource` receives `HttpClient&` instead of constructing a `CurlHttpClient` itself.** This
  is dependency injection: the class does not decide who it talks to, the caller does. It is what makes
  `FakeHttpClient` usable in tests without touching `GutenbergSource`'s code, and it is what a second
  book source (e.g. a different catalog) would reuse verbatim — only `book_download_url`-equivalent
  logic changes per provider, never the transport.
- **`FakeHttpClient` records `requested_urls()` instead of just returning a canned result.** Being able
  to assert the exact URL the code under test asked for is what makes
  `GutenbergSourceTest.AsksTheInjectedClientForTheRightUrl` a real test of `GutenbergSource`'s logic,
  not just of `book_download_url`.
- **`CurlHandle`/`write_callback` moved, not rewritten.** The libcurl mechanics from Entry 9 were
  already tested and explained; this entry is purely about where that code lives and who is allowed to
  call it (only `CurlHttpClient::get`), so nothing about how HTTP is performed changed.
- **Deliberately not adding a second `BookSource` yet (e.g. archive.org).** SPEC section 2 requires only
  Project Gutenberg. Adding a second implementation now, with nothing to plug it into, would be
  speculative generality (YAGNI). The point of this refactor is that adding one later costs one small
  class, not a rewrite of `GutenbergSource` or `CurlHttpClient`.

---

## Entry 11 – Metadata extraction with regex (2026-09-27)

### What was done
- `include/stage1/metadata.hpp`: `BookMetadata` struct (`title`, `author`, `release_date`, `language`,
  each `std::optional<std::string>`) and `extract_metadata(header) -> BookMetadata`.
- `src/metadata.cpp`: implements SPEC section 4's four regexes with `std::regex`, using the
  `multiline` flag so `^`/`$` match line boundaries instead of the whole header, and `ECMAScript`
  (the library default grammar) explicitly alongside it.
- 7 tests: all four fields present, release date with/without the `[...]` note, a missing field
  (nullopt), an empty header (all nullopt), first occurrence wins when a field repeats, and
  whitespace trimming around the value. Suite total: 38 tests.
- Persisting this into SQLite (the `books` table from SPEC section 4) is the next step, not this one.

### Why
- **`std::regex` with `multiline`, applied to the whole header, instead of splitting into lines and
  matching each one.** SPEC labels the patterns "multilínea" and anchors them with `^`/`$`; the
  `multiline` flag makes `std::regex` implement exactly that semantics (line boundaries, not string
  boundaries), so the code mirrors the spec instead of re-deriving equivalent behaviour by hand.
  Because `.` never matches a newline in ECMAScript grammar, "only the first line of the value" comes
  for free from the regex itself, before our own `trim()` even runs.
- **`std::optional<std::string>` per field, not `std::string` with an empty string for "missing".**
  SPEC explicitly distinguishes "missing" (-> SQL `NULL`) from "present but empty"; collapsing both
  into `""` would make that distinction unrepresentable and would silently insert a wrong value into
  the `books` table later.
- **`extract_field` is a private helper, one per call, not one big regex with four groups.** Each SPEC
  field is independent (a book can have `Title` but not `Language`); a single combined regex would
  force all four to be present/absent together, which the spec does not require. It also keeps each
  pattern individually testable and readable, matching the SPEC table one row at a time.
- **`static const std::regex` per pattern, function-local.** Compiling a regex pattern is not free;
  building the four of them once (on the first call, initialized on demand and reused afterwards
  during the program's whole run) instead of on every call matters when this function runs once per
  downloaded book.
- **`std::cmatch` over `const char*` pointers instead of converting `header` to `std::string` first.**
  `header` already comes in as a `string_view` from `split_book`; matching directly against its raw
  pointers avoids an extra full copy of the header text.
- **Release date pattern kept as SPEC wrote it, with the optional bracket group made non-capturing
  (`(?:...)`)** since we only need group 1; behaviour is identical to the spec's version, only the
  unused second capture is dropped.
- **Known limitation, matching Entry 7's note on `trim`:** the fields are compared case-sensitively
  against the exact strings `Title:`, `Author:`, `Release date:`, `Language:`, as SPEC specifies; a
  header using different capitalization would leave that field as `NULL` rather than matching loosely,
  which keeps behaviour predictable and identical across the three languages.

---

## Entry 12 – Persisting metadata into SQLite (2026-09-27)

### What was done
- `include/stage1/metadata_store.hpp` + `src/metadata_store.cpp`: `MetadataStore`, a class owning a
  SQLite connection. Its constructor creates the `books` table and the two indexes from SPEC section 4
  with `CREATE ... IF NOT EXISTS`. `insert_book(id, metadata, body_path, header_path)` writes one row
  (`INSERT OR REPLACE`); `find_by_id(id)` reads one row back as `std::optional<StoredBook>`.
- Internal `Statement`: RAII wrapper around `sqlite3_stmt*`, the same pattern as `CurlHandle` (Entry 9)
  applied to a different opaque C pointer.
- All SQL uses **parameter binding** (`?` placeholders + `sqlite3_bind_*`), never string concatenation.
- 6 tests: round trip of a full row, a missing id, `NULL` fields round-tripping as `nullopt`,
  `INSERT OR REPLACE` overwriting an existing id, values containing apostrophes, and reopening the same
  database file to confirm `IF NOT EXISTS` makes construction idempotent. Suite total: 44 tests.
- Phase 3 (SPEC section 4, metadata) is now complete: extraction (Entry 11) + persistence (this entry).

### Why
- **`sqlite3` forward-declared in the header, `<sqlite3.h>` only included in the `.cpp`.** Nothing
  outside `metadata_store.cpp` needs to know SQLite's C API; this mirrors the earlier decision to keep
  `<curl/curl.h>` inside `curl_http_client.cpp` only, and keeps `MetadataStore`'s header light for
  anyone who just wants to call `insert_book`/`find_by_id`.
- **Parameter binding (`?` + `sqlite3_bind_text`) instead of building the SQL string with `+`.** A book
  title or author can contain an apostrophe (`O'Brien`, `Bob's Book`) or any other character; string
  concatenation would either produce invalid SQL or, in a worse case elsewhere, be a SQL injection
  vector. Binding lets SQLite treat the value purely as data, never as SQL syntax, regardless of its
  content — the dedicated test with apostrophes exists specifically to catch a regression back into
  concatenation.
- **`Statement` (RAII) reused for both `insert_book` and `find_by_id`.** A `sqlite3_stmt*` must be
  finalized exactly once, same reasoning as `CurlHandle`; wrapping it once, deleting its copy operations,
  removes the risk of forgetting `sqlite3_finalize` on an early `return` or a thrown exception.
- **`bind_optional_text`/`column_optional_text` centralize the `nullopt <-> SQL NULL` mapping.** Every
  metadata field goes through the same two functions, so "missing means NULL" (SPEC section 4) is
  enforced in one place instead of four repeated `if` statements.
- **`SQLITE_TRANSIENT` when binding text.** It tells SQLite to copy the string into its own memory
  immediately; the alternative, `SQLITE_STATIC`, would keep a pointer into our `std::string`, which is
  only guaranteed to be alive until `insert_book` returns — using `SQLITE_STATIC` here would be a
  dangling-pointer bug the moment SQLite read the value after that.
- **`INSERT OR REPLACE` instead of plain `INSERT`.** At this stage there is no control layer yet
  (that is Phase 8) to guarantee a book is only ever indexed once; letting a repeated `book_id` replace
  the row instead of throwing a `UNIQUE` constraint error keeps development and testing (and safe
  reruns of the pipeline before Phase 8 exists) simple. This choice should be revisited once the
  control layer exists, since silently replacing could also hide a real bug upstream.
- **`body_path`/`header_path` are plain `std::string`, not `std::optional`.** SPEC section 4's schema
  allows them to be `NULL` in principle, but in this pipeline a row is only ever inserted after a
  successful `split_book`, so both paths are always known; keeping them non-optional makes that
  guarantee visible in the type instead of forcing every caller to unwrap an `optional` that is never
  actually empty in practice.

---

## Entry 13 – Open decision: generalizing MetadataStore (2026-09-28)

### What was discussed (no code change)
Whether to extract a `MetadataRepository` interface (mirroring `HttpClient`/`BookSource` from Entry 10)
around `MetadataStore`, so a future PostgreSQL/MySQL or MongoDB metadata backend could be swapped in
without touching callers. This is the optional "Metadata Storage Comparison" from the course PDF
section 4.1 (SQLite vs PostgreSQL/MySQL vs MongoDB/Redis) — `shared/SPEC.md` section 4 itself only
requires SQLite, so nothing here is a hard requirement.

### Decision
**Deferred, not applied.** Revisit only if the group actually decides to benchmark alternative
metadata backends (which would also need mirroring in Java and Python for a fair comparison, per
SPEC's cross-language contract).

### Why (the two benefits `HttpClient` gave us don't both apply here)
- **Testability benefit does not apply.** `HttpClient` needed a fake because the real network is slow,
  flaky, and not always available (the download test uses `GTEST_SKIP` for exactly that reason).
  SQLite's `":memory:"` mode is already fast, deterministic and dependency-free, so `MetadataStore`'s
  tests already have the equivalent of a "fake" for free, at zero extra cost.
- **Swap-without-rewrite benefit applies only partially.** Unlike swapping one HTTP library for another
  (both just "GET a URL"), PostgreSQL/MySQL use an entirely different C client API from SQLite (network
  connection, auth, its own API) and MongoDB is not even SQL (document model). An interface would spare
  *callers* of `MetadataStore` from changing, but the new backend class itself would still have to be
  written in full either way — the interface saves less future work here than it did for `HttpClient`.
- **Not required by `shared/SPEC.md`.** The comparison is explicitly optional in the course PDF; adding
  the abstraction now, with only one implementation and no concrete plan to add a second, would be
  speculative generality (YAGNI) with a smaller payoff than the `HttpClient` case had.
- **If revisited:** the sketch discussed was `class MetadataRepository { virtual insert_book(...) = 0;
  virtual find_by_id(...) = 0; }` with the current `MetadataStore` renamed to `SqliteMetadataRepository`
  implementing it — a small, mechanical change to apply later if the group commits to the comparison.

---

## Entry 14 – Datalake interface and the book-based layout (2026-09-28)

### What was done
- `include/stage1/datalake.hpp`: `BookLocation` (the two paths a write produced) and the abstract
  `Datalake` interface, one pure virtual method: `write(book_id, header, body) -> BookLocation`.
- `include/stage1/file_io.hpp` + `src/file_io.cpp`: `write_text_file(path, content)`, shared by every
  datalake layout — creates missing parent directories, then writes the file; throws
  `std::runtime_error` on any I/O failure.
- `include/stage1/book_based_datalake.hpp` + `src/book_based_datalake.cpp`: `BookBasedDatalake`, the
  first `Datalake` implementation, for the `book` layout from SPEC section 3
  (`<root>/<ID>/body.txt`, `<root>/<ID>/header.txt`).
- `tests/support/temp_dir.hpp`: `TempDir`, an RAII temporary directory, factored out now because the
  three upcoming datalake tests (book/range/time) all need one, unlike earlier one-off temp files.
- `tests/book_based_datalake_test.cpp`: 3 tests (paths and content are correct, two ids get separate
  directories, writing the same id again replaces the content). Suite total: 47 tests.
- Not done yet: `RangeBasedDatalake`, `TimeBasedDatalake` (next steps of this phase).

### Why
- **`Datalake` as an interface from the start, unlike `MetadataRepository` (Entry 13, deferred).**
  This is the opposite situation: SPEC section 3 explicitly requires comparing three layouts
  (`time`/`book`/`range`) against each other, so a shared interface is not speculative here — it is
  the thing being benchmarked. The Java teammate independently reached the same design ("Reto 7:
  extrae el contrato Datalake"), which confirms this is the natural shape for this specific SPEC
  requirement, not just a preference of this module.
- **`write_text_file` shared instead of duplicated in each layout.** All three layouts do the same two
  things — "make sure the directory exists" and "write a file" — with only the *path* differing between
  them; duplicating that would risk the three layouts handling I/O errors inconsistently.
- **`Datalake::write` returns `BookLocation` instead of `void`.** The caller (later, `MetadataStore`)
  needs `body_path`/`header_path` to store in the `books` table; computing them again outside the
  datalake would duplicate the exact layout logic that only the concrete `Datalake` knows.
- **Throwing on I/O failure, not returning a bool/optional.** A book that cannot be written (disk full,
  permissions) must not be silently treated as if it were: the sample dataset benchmarks in Phase 9
  need to know a write genuinely failed, and swallowing the error would corrupt the
  `downloaded_books.txt` bookkeeping of a later phase (Phase 8) if it marked something as done that
  never landed on disk.
- **`TempDir` extracted into `tests/support/` now, ahead of the range/time tests.** Unlike the
  `MetadataRepository` case, this reuse is concrete and immediate (the very next two steps need the
  exact same helper), not speculative.
- **`BookBasedDatalake` written first, not `TimeBasedDatalake`.** It is the layout with no extra logic
  beyond string concatenation, so `Datalake`'s contract and the write-then-read-back testing pattern
  get verified on the simplest case before adding range arithmetic or a clock dependency.

---

## Entry 15 – Range-based datalake layout (2026-09-29)

### What was done
- `include/stage1/range_based_datalake.hpp` + `src/range_based_datalake.cpp`:
  - `range_folder_name(book_id)` — pure function, no filesystem access, computes `"<INI>-<FIN>"`
    (`INI = (book_id / 1000) * 1000`, `FIN = INI + 999`, both zero-padded to 5 digits) per SPEC section 3.
  - `RangeBasedDatalake : public Datalake` — writes `<root>/<INI>-<FIN>/<ID>.body.txt` and
    `<ID>.header.txt`, reusing `write_text_file` (Entry 14).
- `tests/range_based_datalake_test.cpp`: 4 tests for `range_folder_name` alone (the SPEC example,
  the first range's boundaries, a range start being its own first member, a 5-digit id near the
  current size of Project Gutenberg) and 3 for `RangeBasedDatalake::write` (paths and content,
  two books sharing one range folder without colliding, two books in different ranges getting
  separate folders). Suite total: 54 tests.

### Why
- **`range_folder_name` split out as its own free function, same shape as `book_download_url`
  (Entry 9).** The only genuinely error-prone part of this layout is the integer arithmetic and the
  zero-padding, not the file writing (already covered by `write_text_file`'s own tests); isolating it
  means the 4 arithmetic edge cases (range boundaries, a large id) are checked without touching a
  filesystem at all, and a mistake there cannot hide behind an I/O failure.
- **Integer division (`book_id / 1000`) instead of computing digits by hand.** C++ integer division
  truncates toward zero, so `1342 / 1000 == 1`, `999 / 1000 == 0`; for Gutenberg's positive ids this is
  exactly the "floor to the nearest thousand" SPEC section 3 asks for, and is simpler and less
  error-prone than string-slicing the id.
- **`snprintf("%05d", ...)` for zero-padding instead of manual string building.** It is the standard,
  well-tested way to pad a number in C/C++; hand-rolling padding (prepending `'0'` characters in a
  loop) would be more code for no benefit and another place to get an off-by-one wrong.
- **`zero_pad5` kept in the anonymous namespace of the `.cpp`, not exposed in the header.** Nothing
  outside this file needs to zero-pad a number in isolation; only the combined `"INI-FIN"` string
  is part of the layout's public contract.
- **A dedicated test for the "range start is its own first member" case (`range_folder_name(1000)`).**
  It is the classic boundary where a `<` vs `<=` (or, here, an integer-division rounding direction)
  mistake would show up first; the SPEC's own worked example (1342) does not exercise this boundary.

---

## Entry 16 – Time-based datalake layout, and testing code that depends on the clock (2026-09-29)

### What was done
- `include/stage1/time_based_datalake.hpp`: `Clock` (abstract, one method `now()`), `SystemClock`
  (the real implementation), `time_folder_name(time_point) -> "YYYYMMDD/HH"` (pure), and
  `TimeBasedDatalake : public Datalake`, which takes a `Clock&` in its constructor (dependency
  injection, same shape as `GutenbergSource(HttpClient&)`).
- `src/time_based_datalake.cpp`: `time_folder_name` converts the time point to local calendar time
  with `localtime_r` (POSIX) / `localtime_s` (Windows) — the thread-safe variants of `std::localtime`
  — then formats it with `snprintf`. `TimeBasedDatalake::write` asks `clock_.now()`, builds
  `<root>/YYYYMMDD/HH/<ID>.body.txt` and `.header.txt`, and reuses `write_text_file`.
- `tests/fakes/fake_clock.hpp`: `FakeClock`, a `Clock` that always returns a fixed time chosen by the
  test (same idea as `FakeHttpClient`).
- `tests/time_based_datalake_test.cpp`: 6 tests. Two exercise `time_folder_name` directly through a
  `make_local_time(year, month, day, hour)` test helper; one confirms `SystemClock` is actually wired
  to the real clock (`before <= now() <= after`); three exercise `TimeBasedDatalake::write` with
  `FakeClock`, including two books written in different hours landing in different folders.
  Suite total: 59 tests. This closes Phase 4 (all three datalake layouts from SPEC section 3).

### Why
- **`Clock` interface + `SystemClock`/`FakeClock`, mirroring `HttpClient`/`CurlHttpClient`/
  `FakeHttpClient`.** The real clock is exactly the kind of dependency that makes a test
  non-deterministic if called directly: `time_folder_name(std::chrono::system_clock::now())` would
  produce a different, unpredictable folder name depending on the second the test happened to run,
  and would need special-casing around midnight. Injecting the "what time is it" question, the same
  way we injected "how do I fetch a URL", removes that non-determinism entirely.
- **`time_folder_name` still takes a `time_point` as a plain argument, not a `Clock&`.** Only
  `TimeBasedDatalake::write` needs to ask "what time is it right now"; the formatting logic itself has
  nothing to do with clocks and is more directly testable as a pure function of its input, exactly like
  `range_folder_name` needed no `Datalake` to be tested on its own.
- **`make_local_time` builds its `time_point` via `std::mktime`, and `time_folder_name` reads it back
  via `localtime_r`/`localtime_s` — the same local-time conversion, run in both directions on whichever
  machine the tests happen to execute on.** This is what makes the tests deterministic across timezones
  without needing to inject or hardcode a specific timezone: whatever local rules the test machine (or
  the grading machine) uses, the round trip through those same rules must return the fields we started
  with. Hardcoding an expected `"20260905/08"` string derived from a UTC timestamp would instead be
  correct only on machines set to one specific timezone, and would fail — or worse, silently pass by
  coincidence — elsewhere.
- **`localtime_r`/`localtime_s` instead of `std::localtime`.** `std::localtime` writes its result into
  a single buffer shared by the whole process and is not safe to call from more than one thread at
  once; this stage's pipeline is single-threaded, so it would work today, but unlike the earlier
  decision to defer `curl_global_init`'s thread-safety (Entry 9, where getting it right later requires
  one extra call at `main`), using the safe variant here costs nothing extra now and removes the trap
  entirely rather than deferring it.
- **`SystemClockTest` checks `before <= now() <= after` instead of an exact value.** There is no way to
  assert a wall-clock reading exactly without a race; bracketing it between two calls to the real clock
  is the standard way to confirm `SystemClock` is genuinely wired to `std::chrono::system_clock` and not,
  say, accidentally returning a fixed epoch value, while staying robust to however many microseconds the
  test itself takes to run.

---

## Entry 17 – In-memory inverted index (2026-09-29)

### What was done
- `include/stage1/inverted_index.hpp` + `src/inverted_index.cpp`: `InvertedIndex`, holding
  `term -> std::set<int>` (a `std::unordered_map` keyed by term, each value an ordered,
  de-duplicated `std::set` of book ids). `add_book(book_id, tokens)` indexes every *distinct*
  term of `tokens`; `postings(term)` returns the ids as a sorted `std::vector<int>` (empty, not
  an error, if the term is unknown); `term_count()` returns the number of distinct terms.
- 8 tests: empty index, indexing a book's terms, an unknown term, postings sorted regardless of
  insertion order, a repeated term within one book counted once, re-indexing the same book not
  duplicating its id, `term_count` counting only distinct terms, and a full example built directly
  from `tokenize()`'s output using the theory exercise from the start of this project (the
  `car`/`nice`/`that`/`the`/`is` documents). Suite total: 67 tests.
- Persisting this structure to disk (monolithic/hierarchical/Mongo, SPEC section 6) is the next phase.

### Why
- **`add_book` takes `tokenize()`'s raw output (with repeats) and de-duplicates internally**, instead
  of requiring the caller to pass an already-deduplicated set. SPEC section 5 point 6 says a book
  contributes the *set* of its terms, not a bag with repeats; putting that rule inside `InvertedIndex`
  means the pipeline can simply call `index.add_book(id, tokenize(body, stopwords))`, and the rule is
  enforced in exactly one place instead of trusted to every caller.
- **A local `std::unordered_set<std::string> seen`, scoped to one `add_book` call, guards the inserts.**
  Without it, a common word appearing hundreds of times in one book would call
  `std::set<int>::insert(book_id)` hundreds of times for the same id; each call is a no-op after the
  first (a `std::set` already refuses duplicates) but still costs an O(log n) tree lookup. Checking
  membership in a hash set first (O(1) average) before ever touching the postings set makes indexing a
  large book cheap instead of proportional to its word count times the size of its vocabulary.
- **`std::set<int>` as the postings container, not `std::vector<int>` sorted afterwards.** SPEC section
  6 requires postings "ordered ascending, without duplicates"; a `std::set` keeps both invariants
  automatically on every insert, so there is no separate sort/dedup pass to remember (or forget) when
  the index is later exported to any of the three on-disk formats.
- **`postings` returns `std::vector<int>`, not the internal `std::set<int>&`.** A `std::vector` is
  what the query engine (a later phase, SPEC section 7: intersecting postings lists) and the disk
  writers will actually want to iterate and combine; returning it by value also means callers cannot
  accidentally mutate the index's internal state through a leaked reference.
- **An unknown term returns an empty list rather than throwing.** Looking up a word that appears in no
  book is an entirely normal outcome of a query (SPEC section 7), not a programming mistake — the
  opposite reasoning from `DownloadResult::text()` on a failure (Entry 8), which throws because that
  really is a misuse of the API.
- **The last test builds the index straight from `tokenize()`, using the exact three-document example
  from the very first explanation of this project.** It exercises the seam between two phases already
  built independently (tokenizing and indexing) together for the first time, and ties the code back to
  the mental model the project started from.

---

## Entry 18 – Index persistence: interface and the monolithic JSON writer (2026-09-29)

### What was done
- `InvertedIndex` extended with `IndexEntry` (a `term` + its `postings`) and `entries()`, which
  snapshots every term with its sorted postings as `std::vector<IndexEntry>`. Needed because, until
  now, the index could only be queried term by term; a writer needs to walk the whole thing. Added a
  matching test (`EntriesReturnsEveryTermWithSortedPostings`, order-independent via a `std::map`).
- `include/stage1/index_writer.hpp`: abstract `IndexWriter`, one method `write(const InvertedIndex&)`.
  Mirrors `Datalake` (Entry 14): SPEC section 6 explicitly requires comparing three on-disk structures
  for the index, same reasoning as the three datalake layouts.
- `include/stage1/monolithic_index_writer.hpp` + `src/monolithic_index_writer.cpp`:
  `MonolithicIndexWriter`, the first `IndexWriter`. Writes the whole index as one JSON object
  (`{"term": [id1, id2, ...], ...}`) using `nlohmann::json`, reusing `write_text_file` (Entry 14).
- `tests/monolithic_index_writer_test.cpp`: 3 tests (postings round-trip correctly through the JSON
  file, an empty index writes `{}` rather than `null`, missing parent directories get created).
  Suite total: 71 tests.

### Why
- **`entries()` returns a snapshot (`std::vector<IndexEntry>`), not iterators into `index_`.** Exposing
  `index_.begin()/end()` directly would leak the internal `std::unordered_map<std::string, std::set<int>>`
  type to every writer, coupling them to an implementation detail that might change (e.g. if the index
  were later reorganized for performance); a plain vector of a small public struct is a stable contract.
- **`IndexWriter` as an interface, unlike `MetadataRepository` (Entry 13, deferred).** Same reasoning as
  `Datalake`: SPEC section 6 requires comparing monolithic/hierarchical/Mongo against each other, so
  the abstraction is not speculative here, it is the object of the comparison itself. A concrete writer
  takes an `InvertedIndex` (not, say, the raw `index_` map), keeping every writer's contract identical
  regardless of how the in-memory index is stored internally.
- **`nlohmann::json::object()` explicitly, instead of a default-constructed `nlohmann::json`.** A
  default `nlohmann::json` is a `null` value; assigning nothing to it (an index with zero terms) would
  `dump()` as the string `"null"`, not `"{}"`. The dedicated empty-index test exists to catch exactly
  this if it regressed.
- **Compact `dump()`, no pretty-printing.** SPEC section 6 does not ask for the file to be
  human-readable, and this file is written and read by programs, potentially with hundreds of
  thousands of terms; indentation would only add parsing cost and disk usage for no benefit, which
  matters directly for the `index_disk`/`datalake_storage`-style benchmarks in Phase 9.
- **`MonolithicIndexWriter(path).write(index)` takes the path in the constructor, `write` takes only
  the index.** The output location is a property of *which* writer you built (where the monolithic file
  goes), not of each call; this mirrors `BookBasedDatalake`/`RangeBasedDatalake` taking `root` in their
  constructor and `write` taking only what varies per book.

---

## Entry 19 – Hierarchical index writer (2026-09-29)

### What was done
- `include/stage1/hierarchical_index_writer.hpp` + `src/hierarchical_index_writer.cpp`:
  - `hierarchical_folder_name(term)` — pure function, no filesystem access, uppercases `term`'s
    first character (`"car"` -> `"C"`; a leading digit uppercases to itself, so `"1876"` -> `"1"`).
  - `HierarchicalIndexWriter : public IndexWriter` — writes `<root>/<LETTER>/<term>.txt`, one book
    id per line, reusing `write_text_file` (Entry 14).
- `tests/hierarchical_index_writer_test.cpp`: 2 tests for `hierarchical_folder_name` alone (a normal
  letter, a leading digit) and 4 for the writer (postings written one per line, two terms sharing a
  first letter landing in the same folder without colliding, two different letters getting separate
  folders, an empty index writing nothing and not throwing). Suite total: 77 tests.
- Phase 6 now has two of its three required writers (monolithic, hierarchical); MongoDB is the
  remaining optional one, per SPEC section 6 ("al menos 3", with Mongo explicitly listed as one option).

### Why
- **`hierarchical_folder_name` split out as a pure function, same shape as `range_folder_name` (Entry
  15) and `book_download_url` (Entry 9).** The one part of this writer that is easy to get subtly
  wrong is the folder-name rule itself (what happens to a digit, to case); isolating it lets the two
  edge cases be checked without touching a filesystem, the same reasoning applied three times now
  across the project.
- **Trusting that every `term` contains only `[a-z0-9]`, instead of validating or sanitizing it inside
  this writer.** This is a different situation from `MetadataStore`'s SQL binding (Entry 12): a book's
  title comes from an external, untrusted source (Project Gutenberg) and could contain anything, so it
  had to be defended against explicitly. A `term` here is never external — it only ever comes from
  `stage1::tokenize`'s own output, which the Phase 1 tokenizer already guarantees is restricted to
  `[a-z0-9]` (Entry 3). Re-validating an invariant that is already enforced, and enforced by code in
  the same project, would be redundant; the comment on `hierarchical_folder_name` documents the
  assumption explicitly instead, so it stays visible if `tokenize`'s contract ever changes.
- **`unsigned char` before calling `std::toupper`.** `char` can be negative on this platform for a
  non-ASCII byte, and `std::toupper`'s behavior for such a value is undefined by the C standard. Not
  reachable through the real pipeline (see the point above), but costs nothing to get right in the
  function itself, in the same spirit as using `localtime_r` over `std::localtime` even though the
  pipeline is single-threaded today (Entry 16).
- **One `write_text_file` call per term, no attempt to batch or hold multiple files open at once.**
  Keeps this writer as simple as `MonolithicIndexWriter`, reusing the exact same building block; SPEC
  section 6 itself frames the many-small-files cost ("a very large number of small files can overwhelm
  the filesystem") as something to *measure* in Phase 9, not to optimize away before it is measured.

---

## Entry 20 – Group decision: MongoDB runs via Docker (2026-09-29)

### What was decided (no code yet)
The group has decided that, when the optional MongoDB index structure (SPEC section 6) is built, it
will run inside a Docker container rather than each member installing MongoDB natively. This entry
records the decision and its rationale for the report's design-decisions section; the actual
`MongoIndexWriter` implementation (behind the existing `IndexWriter` interface, Entry 18) is still
deferred.

### Why
- **Reproducibility across the group and the grading machine.** Three people (Java, Python, C++) and
  the professor's machine all get the exact same MongoDB version and configuration from one
  `docker-compose.yml`, instead of each environment's native install potentially drifting.
- **Disposable state for benchmarks.** Phase 9's benchmarks need a clean, empty index to measure
  writes from scratch repeatedly; a container can be torn down and recreated in seconds, without
  leftover data or needing to manually `DROP` collections on a shared local install.
- **No change to application code.** From C++'s point of view, MongoDB is just a server reachable at
  a host:port (typically `localhost:27017`); the eventual `MongoIndexWriter` connects the same way
  whether MongoDB runs natively or inside Docker, so this decision does not affect the design already
  in place (`IndexWriter`), only how the database is started for development, testing and grading.

---

## Entry 21 – MongoDB index writer, and a real bug caught by testing (2026-09-29)

### What was done
- `docker-compose.yml` (repository root, shared by the whole group): a single `mongo:7` service on
  port 27017 with a named volume, `docker compose up -d` / `down -v` as documented in the file itself.
- Installed the official `mongo-cxx-driver` (and its `mongo-c-driver` dependency) via Homebrew; wired
  `find_package(mongocxx REQUIRED)` / `find_package(bsoncxx REQUIRED)` and linked
  `mongo::mongocxx_shared` / `mongo::bsoncxx_shared` into `stage1_deps`. Unlike `nlohmann_json` and
  `googletest`, this is **not** fetched via `FetchContent`: it is a large driver with its own native
  dependencies (TLS, SASL), and Homebrew already ships a prebuilt, versioned bottle for it, the same
  reasoning already applied to `SQLite3`/`CURL`.
- `include/stage1/mongo_index_writer.hpp` + `src/mongo_index_writer.cpp`: `MongoIndexWriter`, the third
  `IndexWriter`. Connects to a URI (default `mongodb://localhost:27017`), writes to database
  `search_engine`, collection `inverted_index`, one document per term
  (`{"term": "...", "postings": [ids...]}`), with a unique index on `term`; `write()` clears the
  collection first, so repeated calls fully replace its contents, matching how the other two writers
  overwrite rather than append. MongoDB-specific exceptions are caught and rethrown as
  `std::runtime_error`, keeping one error type across all three writers.
- `ensure_mongo_driver_initialized()`, declared in the header, defined once in the `.cpp`: the driver
  requires exactly one `mongocxx::instance` alive per process, created before any other mongocxx
  object; every place that touches mongocxx (the writer, and the test file's own read-back client)
  calls this single function instead of each creating its own.
- `tests/mongo_index_writer_test.cpp`: 3 tests. Two need a reachable MongoDB and `GTEST_SKIP` if there
  is none (same pattern as the real Gutenberg download test), checking a full write/read-back round
  trip and that a second `write()` replaces the first. The third needs MongoDB to be *unreachable* by
  design (a bad URI with a short `serverSelectionTimeoutMS`) and always runs, checking the
  `std::runtime_error` translation. Suite total: 80 tests.

### A real bug this caught
The first version had **two separate** function-local `static mongocxx::instance` variables: one
inside `mongo_index_writer.cpp`, another inside the test file (which also needs mongocxx to read the
collection back). Running the tests through `ctest` looked fine, because `gtest_discover_tests` runs
every `TEST()` as its **own process**, so the two statics never coexisted. Running the whole test
binary directly in one process (`./stage1_tests --gtest_filter="MongoIndexWriter.*"`) crashed
immediately with `cannot create a mongocxx::instance object if one has already been created`, because
both statics got constructed in the same process. Fixed by exposing one shared
`ensure_mongo_driver_initialized()` from the library itself and having every caller, including the
test file, go through it — so there is exactly one function-local static in the whole program, no
matter how many places call the function.

### Verification
No Docker on this machine, so the code could not be exercised against the group's actual
`docker-compose.yml`; verified instead against a temporary local `mongod` (Homebrew-installed,
`--dbpath` in `/tmp`, stopped and its data directory removed afterward) — equivalent from the driver's
point of view, since `MongoIndexWriter` only ever sees a URI, exactly the reasoning in Entry 20. All
80 tests passed against it, including the two that need a live server. This should be re-verified
against the real `docker compose up -d` MongoDB the next time this runs on a machine that has Docker.

### Why
- **Homebrew instead of `FetchContent` for this one dependency**, breaking the pattern used for
  `nlohmann_json`/`googletest`. Building `mongo-cxx-driver` from source pulls in `mongo-c-driver`, TLS
  and SASL as further dependencies and is known to be slow and finicky to configure via CMake; a
  prebuilt bottle avoids all of that at the cost of one extra install step documented for teammates.
- **`ensure_mongo_driver_initialized()` exposed from the header, not hidden as a private implementation
  detail.** It has to be callable from outside the class (the test file needs it too), and hiding it
  would only tempt a second, incompatible definition to reappear elsewhere later, exactly the bug this
  entry describes.
- **Catching `mongocxx::exception` and rethrowing `std::runtime_error`.** Callers of `IndexWriter::write`
  (the future indexing pipeline, and benchmarks) should not need to know or `#include` mongocxx-specific
  exception types to handle a failure from any of the three writers uniformly.
- **`delete_many` + reinsert on every `write()`, not an incremental diff.** SPEC section 6's benchmark
  considerations explicitly want *update* performance measured separately from *build* performance
  (Phase 9); keeping `write()` as "replace everything" now, matching the other two writers, keeps all
  three comparable on the same operation, and an incremental update path can be added later as its own
  benchmarked operation rather than folded silently into `write()`.
- **The unreachable-server test always runs (no `GTEST_SKIP`), unlike the other two.** It needs
  MongoDB to be absent to prove anything, the opposite precondition from the round-trip tests, so it is
  the one MongoDB test that is meaningful and stable in every environment, Docker or not.

---

## Entry 22 – AND query engine (2026-09-29)

### What was done
- `include/stage1/query_engine.hpp` + `src/query_engine.cpp`: `query_and(index, terms)`, implementing
  SPEC section 7. De-duplicates `terms`, fetches each distinct term's postings from `InvertedIndex`,
  sorts the lists smallest-first, and folds them together with `std::set_intersection`, short-circuiting
  as soon as the running result is empty.
- 7 tests: a single term, a real intersection (`car`+`nice`), an unknown term collapsing the whole
  query to empty, an empty term list, a repeated term behaving like one, a three-term query staying
  sorted, and — closing the loop again — the exact `query_and(index, tokenize("car nice", stopwords))`
  example this project started from, now returning `{1}` for real. Suite total: 87 tests.

### Why
- **Takes `terms` (already tokenized), not a raw query string plus a stopword set.** Same reasoning as
  `InvertedIndex::add_book`: tokenizing and indexing/querying are separate concerns, so a caller does
  `query_and(index, tokenize(query_text, stopwords))` — SPEC section 7's "the query is tokenized with
  the same tokenizer" is satisfied by *reusing* `tokenize`, not by `query_and` reimplementing it.
- **Empty `terms` returns no results, not every book.** The intersection of zero sets is mathematically
  undefined (it would be "the universe"); for a search engine, a query with no meaningful terms (an
  empty string, or only stopwords) has nothing to search for, so returning nothing is the sensible,
  unsurprising behavior, and is the interpretation a user would expect.
- **Query terms de-duplicated before any postings lookup.** `query_and(index, {"car", "car"})` must
  behave exactly like `{"car"}`; without de-duplicating first, the same postings list would be fetched
  and intersected with itself for no benefit, wasted work that grows with how many times a common word
  repeats in a real query.
- **Smallest postings list first, short-circuiting on an empty result.** This is the standard technique
  for AND queries over sorted postings: once the running intersection is empty, no later term can add
  anything back, so remaining (possibly much larger) lists are never even touched. It directly reduces
  the cost SPEC section 9's `index_query` benchmark measures, and a rare term combined with a very
  common one is exactly the case a search engine sees most often.
- **`std::set_intersection` over two sorted ranges, not a hash-set-based intersection.** `postings()`
  already guarantees ascending order (Entry 17); a linear merge of two sorted sequences is the natural,
  allocation-light way to intersect them, and keeps the result sorted automatically without a separate
  sort step, unlike building a hash set that would need sorting again before comparison or output.
- **The final test re-runs the project's very first exercise end to end** (index the three theory
  documents, then AND-query "car nice"), now through real code instead of by hand, tying tokenizing,
  indexing and querying together for the first time in one place.

---

## Entry 23 – Control log (2026-09-29)

### What was done
- `include/stage1/control_log.hpp` + `src/control_log.cpp`: `ControlLog`, one class reused for both
  `downloaded_books.txt` and `indexed_books.txt` (SPEC section 8). Loads existing ids into an
  in-memory `std::unordered_set<int>` on construction; `contains(id)` is an in-memory check;
  `mark(id)` appends to the file and updates memory, but is a no-op if `id` was already recorded.
- 7 tests: starts empty for a missing file, `mark` writes to disk and updates memory, a reload after
  "restarting" still sees a previously marked id, marking the same id twice does not duplicate the
  line, several ids all survive a reload, blank lines in a hand-edited file are ignored while loading,
  and missing parent directories (`control/`) are created. Suite total: 94 tests.
- Not done yet: the actual decision logic ("what should the pipeline do next", SPEC section 8.2) that
  uses two `ControlLog`s together — planned as the next step of this phase.

### Why
- **One class for both files, not two separate ones ("DownloadedBooksLog", "IndexedBooksLog").** The
  two files have identical rules (SPEC section 8: one id per line, append-only, no duplicates); the only
  difference is which path each is constructed with. A single, path-parameterized class avoids
  duplicating that logic and keeps it in one place to test.
- **`mark(id)` is a no-op when `id` is already recorded, instead of always appending.** This is what
  makes the "write the work, then mark it" discipline (discussed before writing any code for this
  phase) actually safe to retry: if the pipeline crashes right after `mark()` succeeded but before the
  caller could move on, or if a caller mistakenly calls `mark()` twice for the same id, the file never
  grows a duplicate line. SPEC section 8 states this guarantee explicitly ("recuperación sin pérdidas
  ni duplicados"); this is the piece of code that enforces the "sin duplicados" half of it.
- **An in-memory `std::unordered_set<int>`, not re-reading the file on every `contains()` call.** The
  control layer will call `contains()` once per candidate book on every pipeline step (SPEC section
  8.2's decision logic); re-parsing a file that can grow to tens of thousands of lines on every check
  would make that decision loop itself a bottleneck, exactly the kind of cost Phase 9's benchmarks
  would otherwise have to explain away as an artifact of this class rather than of the structures being
  compared.
- **`std::from_chars` instead of `std::stoi`.** `std::stoi` throws `std::invalid_argument` on anything
  it cannot parse, which would need a `try`/`catch` around every line just to skip a malformed one;
  `std::from_chars` reports failure through its return value, matching the `sqlite3_open`/`curl_easy_
  perform`-style "check the result, don't rely on exceptions for expected outcomes" reasoning already
  used elsewhere (Entries 9, 12) for common, not-truly-exceptional situations — and a stray blank or
  malformed line in a control file, from a manual edit or an interrupted write, is exactly that.
- **Blank lines silently skipped while loading, no special handling for `#` comments.** SPEC section 8
  does not mention comments for control files (unlike section 1's shared dataset files, which
  explicitly do); skipping blanks defensively costs nothing and guards against a stray trailing newline,
  while not inventing a comment syntax the SPEC never asked for.
- **Directories created in the constructor, not in `mark()`.** `control/` needs to exist before the
  first `mark()` call regardless of whether any id ends up being recorded in a given run; doing it once
  up front, mirroring the PDF's own pseudocode (`CONTROL_PATH.mkdir(parents=True, exist_ok=True)`),
  keeps `mark()` itself focused on the one thing its name says it does.

---

## Entry 24 – Control decision logic and the shared book id list (2026-09-29)

### What was done
- `include/stage1/book_id_list.hpp` + `src/book_id_list.cpp`: `load_book_ids(path)`, reading
  `shared/book_ids.txt` in file order (order matters: "mismo orden en los tres lenguajes", SPEC
  section 1), skipping blank/`#` lines, reusing `trim` and `std::from_chars` exactly like
  `load_stopwords` (Entry 5) and `ControlLog`'s own loading (Entry 23). Deliberately used **instead**
  of the course PDF's `random.randint(1, TOTAL_BOOKS)` approach for picking a new candidate: SPEC
  itself already supplies a deterministic, ordered dataset shared across all three languages, and using
  it keeps the comparison fair the same way Entry 7 chose SPEC's stricter body-start rule over the
  PDF's simpler example.
- `control_log.hpp`/`control_log.cpp` extended with `ControlAction` (`IndexBook`/`DownloadBook`/
  `Nothing`), `ControlDecision` (an action plus a `book_id`), and `next_control_action(candidate_ids,
  downloaded, indexed)`: a pure function (with respect to I/O — it only calls `contains()`, it performs
  no action) implementing SPEC section 8.2 / the PDF's `control_pipeline_step`: index the first
  downloaded-but-not-indexed candidate if one exists, otherwise download the first not-yet-downloaded
  candidate, otherwise there is nothing left to do.
- 9 new tests: 4 for `load_book_ids` (order preserved, comments/blanks skipped, missing file throws, the
  real `shared/book_ids.txt` loads correctly) and 5 for `next_control_action` (pure download pick,
  skipping an already-downloaded candidate, preferring indexing when something is ready, indexing
  taking priority even when other candidates could still be downloaded, and the terminal "nothing to
  do" state). Suite total: 103 tests.
- Not done yet: wiring this decision to the real components (`BookSource`, `Datalake`,
  `MetadataStore`, `InvertedIndex`, `IndexWriter`) into an actual runnable pipeline in `main.cpp` — that
  assembly is a separate, larger task from the control layer's own logic and its own step.

### Why
- **`next_control_action` takes `candidate_ids`, `downloaded` and `indexed` and returns a decision,
  instead of performing the download/index itself.** Separating "decide what to do" from "do it" keeps
  this function pure and trivially testable with real (TempDir-backed) `ControlLog`s and no network,
  filesystem writes beyond the control files themselves, or index/datalake machinery — the same
  "decide vs. execute" split already used for `DownloadResult` (a result the caller acts on) and for
  `GutenbergSource` (decides nothing about storage, only fetches).
- **Real `ControlLog` instances in the tests, not a fake/mock.** `ControlLog` is already fast,
  deterministic and self-contained (a `TempDir`-backed file), exactly the same reasoning Entry 13 used
  to justify *not* building a fake for `MetadataStore`'s SQLite: a test double earns its cost only when
  the real thing is slow, flaky, or unavailable, none of which apply here.
- **Indexing checked before downloading, in that exact order.** SPEC section 8.2 (and the PDF's own
  pseudocode) gives indexing priority: a book already sitting on disk, downloaded but not yet indexed,
  represents work that is closer to finished and cheaper to complete than fetching a brand new book
  over the network; `IndexingTakesPriorityEvenWhenOtherCandidatesCouldStillBeDownloaded` exists
  specifically to pin this ordering down, since swapping the two loops would still pass every other test.
- **`candidate_ids` order decides which book to pick, rather than "any" downloaded-but-not-indexed
  book.** SPEC's shared dataset is deliberately ordered identically across the three languages; picking
  deterministically by that order (instead of, say, whichever id a hash set happens to iterate first)
  keeps which book gets processed next reproducible run to run and comparable language to language,
  which matters for the benchmarks in Phase 9 to be measuring the same work in every language.
- **`ControlDecision::book_id` documented as "meaningful only when action != Nothing"**, rather than
  wrapping it in `std::optional<int>`. `Nothing` already carries no useful `book_id`by construction (no
  candidate qualifies), and every caller must switch on `action` first regardless; an `optional` here
  would only add a second way to represent the same "there is nothing to act on" fact already carried
  by the enum, without preventing any additional mistake.

---

## Entry 25 – main.cpp: wiring everything into a runnable pipeline (2026-09-30)

### What was done
- `include/stage1/file_io.hpp`/`.cpp`: added `read_text_file(path)`, the missing symmetric
  counterpart to `write_text_file` (Entry 14), needed to read a book's body back off the datalake
  before indexing it.
- `include/stage1/pipeline.hpp` + `src/pipeline.cpp`: `run_pipeline_step(candidate_ids, downloaded,
  indexed, source, datalake, metadata, index, index_writer, stopwords)`. Calls
  `next_control_action` (Entry 24) and performs exactly one of:
  - **Download**: `source.fetch` -> `split_book` -> `datalake.write` -> `extract_metadata` ->
    `metadata.insert_book` -> `downloaded.mark`. A failed fetch or a book missing its START/END
    markers is left unmarked on purpose (retried on a future run), never throws.
  - **Index**: `metadata.find_by_id` -> `read_text_file` the stored body -> `tokenize` -> `index.
    add_book` -> `index_writer.write` (rewrites the whole structure) -> `indexed.mark`.
  - **Nothing**: no-op.
  Every dependency is a reference parameter (`BookSource&`, `Datalake&`, `MetadataStore&`,
  `InvertedIndex&`, `IndexWriter&`), the same shape used everywhere else in this project, so it can
  be exercised with fakes and temporary directories instead of the real network or database.
- `src/main.cpp` rewritten: a thin CLI (`search_engine_stage1 pipeline <N>`, mirroring the Java
  module's `pipeline <N>` command) that wires the real components — `CurlHttpClient` +
  `GutenbergSource`, `BookBasedDatalake`, `MetadataStore` (SQLite), `MonolithicIndexWriter` (JSON) —
  loads `shared/stopwords.txt` and `shared/book_ids.txt`, rebuilds the in-memory `InvertedIndex` from
  every already-indexed book's stored body (there is no on-disk index *reader*, only writers, so this
  is the simplest correct way to resume with a populated index), then calls `run_pipeline_step` up to
  `N` times, stopping early once there is nothing left to do.
- `tests/pipeline_test.cpp`: 5 tests against a `PipelineFixture` (temp directories, in-memory SQLite
  path, a `FakeHttpClient` returning a small but realistic Gutenberg-shaped fake book) — a full
  download-then-index round trip through every component, "nothing left to do" once both steps are
  done, and a failed download / a markerless book each left unmarked. Suite total, before the fix
  below: 108.
- `CMakeLists.txt`: `pipeline.cpp` added to `stage1_core`; `STAGE1_SHARED_DIR`/`STAGE1_DATA_DIR`
  compile definitions added to the **executable** (mirroring the tests' own `STAGE1_SHARED_DIR`), so
  the binary finds `shared/` and its own `data/` directory regardless of the working directory it is
  launched from.

### A real bug this caught: `MetadataStore` never created its own directory
Running the assembled binary for real (`./search_engine_stage1 pipeline 4`, real network, a fresh
`cpp/data/`) failed immediately: `cannot open metadata database: unable to open database file`.
`MetadataStore`'s constructor (Entry 12) opened `sqlite3_open` directly, unlike every other
path-taking constructor in the project (`ControlLog`, `write_text_file`'s callers), which all create
missing parent directories first. Its own unit tests never caught this because they always used
`":memory:"` or a path directly inside an already-created `TempDir`, never a path with a
not-yet-existing subdirectory like `datamarts/metadata.db`. Fixed by creating the parent directory in
the constructor (skipped for `":memory:"`, whose `parent_path()` is empty), and added
`MetadataStore.CreatesMissingParentDirectories`, the same test shape already used for `ControlLog`
and `MonolithicIndexWriter`. Suite total after the fix: 109.

### Verification
After the fix, `./search_engine_stage1 pipeline 4` ran against the real network end to end and
downloaded, split, stored the metadata of, and indexed two real books (1342, *Pride and Prejudice*;
84, *Frankenstein*), producing a correct `control/`, `datalake/book/`, `datamarts/metadata.db` and
`datamarts/inverted_index.json` (10114 terms) under `cpp/data/` (git-ignored). Spot-checked: `"whale"`
-> `[84]` only; `"elizabeth"` -> `[84, 1342]` (a character in both books — a nice, unplanned
confirmation that indexing and querying are both working on real text). The data directory was
removed afterward; it was only a verification artifact, not a deliverable.

### Why
- **`run_pipeline_step` lives in `stage1_core`, `main.cpp` stays thin and untested.** All of this
  phase's real logic is a testable library function taking references, exactly like every other piece
  of this project (`GutenbergSource`, `next_control_action`, ...); `main.cpp` only wires concrete
  types together and drives a loop, with nothing left in it worth a unit test of its own.
- **A failed download or a markerless book is never marked, and does not throw.** This is the
  "write first, mark after" discipline (discussed before Phase 8's first line of code) applied at the
  level that actually matters: the pipeline's job is to make progress on what it *can* do and quietly
  leave problem books for a future retry, not to crash the whole run over one bad id.
- **The in-memory index is rebuilt from stored bodies on every startup, not loaded from a saved
  snapshot.** This stage built writers for the three on-disk index formats but no matching readers;
  reading each already-indexed book's body back and re-tokenizing it is slower but requires no new
  format-specific parsing code, and is correct by construction since it goes through the exact same
  `tokenize`/`add_book` path indexing itself uses. Documented as a known cost, not hidden: a real
  pipeline resuming a large, already-indexed collection would pay for this every restart, and adding
  an index reader (or a private fast snapshot format) would be a natural improvement for a later stage.
- **`index_writer.write(index)` rewrites the entire index on every single indexed book, not an
  incremental update.** Direct consequence of `IndexWriter`'s existing contract (Entry 18: "write()
  means make the structure match this index, not append"); correct, but means indexing N books costs
  more here than it would with a true incremental writer. SPEC section 9 explicitly asks for
  `index_update` to be benchmarked as its own operation — this pipeline's current behavior is exactly
  the kind of cost that benchmark exists to surface, not something to silently optimize away before it
  is measured.
- **`BookBasedDatalake` and `MonolithicIndexWriter` chosen as `main`'s defaults, not because they are
  "the best" ones.** Any `Datalake`/`IndexWriter` works identically from the pipeline's point of view
  (it always goes through the interface, never a concrete type), since indexing always reads the body
  back via the path `MetadataStore` stored rather than assuming a particular layout. Swapping either
  default is a two-line change in `main.cpp`; Phase 9's benchmarks are what will actually exercise and
  compare all three alternatives of each, not this single operational pipeline.
- **CLI shape (`pipeline <N>`) mirrors the Java module's own `pipeline <N>` command** (see the root
  `README.md`), keeping how the three language implementations are invoked recognizably similar for
  whoever runs and compares them, including the grader.

*(Addendum to Entry 25)* Also fixed `Makefile`'s `run` target, left stale by the CLI change above: it
called the binary with no arguments, which now just prints usage and exits 1. It now runs
`pipeline 5` by default, overridable with `make run ARGS="pipeline 20"`.

---

## Entry 26 – Benchmark infrastructure: the timer and the shared CSV writer (2026-09-30)

### What was done
- `include/stage1/benchmark.hpp` + `src/benchmark.cpp`:
  - `BenchmarkResult`: one struct per SPEC section 9's CSV columns (`language`, `experiment`,
    `structure`, `dataset_size`, `repetition`, `metric`, `value`, `unit`).
  - `write_benchmark_results(path, results)`: writes the shared header plus one row per result,
    fixed-point with 3 decimals, reusing `write_text_file` (Entry 14).
  - `measure_elapsed_ms(operation, warmup_runs=2, measured_runs=5)`: runs `operation` (any
    zero-argument callable) `warmup_runs` times and discards those, then `measured_runs` times,
    timing each with `std::chrono::steady_clock`, and returns the measured elapsed times in
    milliseconds. Defaults are exactly SPEC section 9's shared methodology (N_WARMUP=2, N_RUNS=5).
- 7 tests: the CSV round-trips exactly (including the fixed 3-decimal formatting), an empty result
  list still writes the header alone, missing parent directories are created, the returned vector has
  exactly `measured_runs` entries, the defaults call the operation `2 + 5` times total, a custom
  warmup/measured pair calls it that many times total, and every measured value is non-negative.
  Suite total: 116 tests.
- Not done yet: the actual per-experiment benchmarks (`datalake_write`, `index_build`, `index_query`,
  ...) that will call `measure_elapsed_ms` and feed its output into `write_benchmark_results` — planned
  as the next steps of this phase, one or a few experiments at a time.

### Why
- **A separate, reusable `measure_elapsed_ms` instead of hand-timing each experiment.** SPEC section 9
  fixes one methodology (2 discarded warmup runs, 5 measured runs) for every experiment in every
  language; writing that loop once here means every later benchmark shares the exact same warmup/
  measurement discipline by construction, instead of each experiment's code having to remember to
  replicate it correctly.
- **`std::chrono::steady_clock`, not `system_clock` (the one `TimeBasedDatalake`'s `Clock` uses).**
  `steady_clock` is guaranteed to never jump backward (e.g. from a system clock adjustment or daylight
  saving) and is meant specifically for measuring durations; `system_clock` is for knowing what time it
  is, which is why `TimeBasedDatalake` (Entry 16) uses it for calendar dates and this uses the other
  for elapsed time — the two clocks solve different problems even though both come from `<chrono>`.
- **Takes a `std::function<void()>`, so it works for any experiment.** A datalake write, an index
  build, a metadata query — every one of SPEC section 9's experiments is, from the timer's point of
  view, "some operation to run and time"; the operation being generic here is what avoids writing a
  bespoke timing loop for each of the twelve named experiments.
- **Fixed-point, 3-decimal CSV output instead of the stream's default formatting.** `double`'s default
  `operator<<` formatting switches to scientific notation for some values (`1.23e+04`), which a naive
  CSV/spreadsheet reader would need to special-case; three decimals keeps microsecond resolution on
  millisecond-scale timings (this project's realistic range) while always being one plain, readable
  number.
- **No CSV quoting/escaping for the string fields.** Every field written here (`language`, `experiment`,
  `structure`, `metric`, `unit`) is always one of this project's own fixed identifiers, never text from
  an external, untrusted source (unlike, say, a book title from Gutenberg, which is exactly why
  `MetadataStore`, Entry 12, needed SQL parameter binding); there is nothing here that could ever
  contain a stray comma to corrupt the format.
- **`write_benchmark_results` reuses `write_text_file` rather than writing incrementally row by row.**
  Consistent with every other writer in this project (`MonolithicIndexWriter`, `HierarchicalIndexWriter`):
  build the full content, then write it once. A benchmark run produces at most a few dozen rows (5
  measured repetitions per structure per experiment), so there is no realistic case where building the
  string first would matter for memory.

---

## Entry 27 – First real experiment: index_build (2026-09-30)

### What was done
- `mongo_is_reachable(uri)` promoted from a test-only helper (Entry 21) to the library
  (`mongo_index_writer.hpp`/`.cpp`): a quick ping with a short `serverSelectionTimeoutMS`, so code can
  skip Mongo-dependent work gracefully instead of waiting out the driver's ~30s default timeout or
  crashing. `mongo_index_writer_test.cpp` now calls this shared function instead of its own copy, and
  gained its own direct test (`MongoIsReachable.ReturnsFalseForAnUnreachableAddressWithoutThrowing`).
- `include/stage1/index_build_benchmark.hpp` + `src/index_build_benchmark.cpp`: `SampleBook` (an
  already-downloaded, already-split book — id + body, no network involved) and
  `benchmark_index_build(language, books, stopwords, output_dir)`, SPEC section 9's `index_build`
  experiment. For each required structure — monolithic, hierarchical, and mongo only if
  `mongo_is_reachable()` — it runs `measure_elapsed_ms`'s default 2+5 repetitions of "build a fresh
  `InvertedIndex` from `books` and persist it through that structure's `IndexWriter`", and appends one
  `BenchmarkResult` per measured run. Mongo unreachable is a silent skip, not a failure: the other two
  structures still get benchmarked.
- 3 tests (`tests/index_build_benchmark_test.cpp`) with a small in-memory corpus (no network, no
  `sample_dataset/`, which does not exist in the repository yet — see below): five measured rows each
  for monolithic/hierarchical with the right shape (language, experiment, dataset_size, metric, unit,
  ascending repetition numbers, non-negative values), Mongo rows present or absent depending on whether
  it happens to be reachable at test time, and the resulting monolithic JSON file re-read and checked
  for correct content. Suite total: 120 tests.
- **Known gap, not filled by this step:** `sample_dataset/` (mentioned in the root `README.md` and
  required by SPEC section 9's own methodology: "las descargas de red se miden aparte... se parte de
  los libros ya descargados en `sample_dataset/`") does not exist anywhere in the repository yet, in any
  of the three languages. This function is deliberately corpus-agnostic (it takes `books` as a plain
  parameter) so it does not need that decision made to be written and tested; wiring an actual CLI
  command that loads real books from `sample_dataset/` (or, meanwhile, from an already-populated
  `cpp/data/datalake/`) is separate follow-up work once the group settles on where that shared sample
  lives.

### Why
- **`mongo_is_reachable` moved to the library instead of staying duplicated per test file.** A second
  caller (this benchmark) needing the exact same check is precisely the point where a test-local helper
  earns its place in `stage1_core` instead — the same threshold already crossed by `TempDir` (Entry 14)
  and `FakeHttpClient`/`FakeClock` once a second test needed them, except this time the second caller is
  production code, not another test.
- **Mongo skipped, not required, for this experiment to produce results.** SPEC's own benchmark
  methodology explicitly separates network-dependent setup from what is measured; requiring a live
  MongoDB (via Docker or a local `mongod`) just to get *any* `index_build` numbers would make the
  benchmark unusable on a machine without either, exactly this machine's situation today (no Docker).
  The two required, always-available structures still produce full results.
- **Each repetition rebuilds the `InvertedIndex` from scratch (tokenize + `add_book` for every book),
  not just the `write()` call.** SPEC/the course PDF describe `index_build` as "time required to build
  the inverted index from a given dataset" — the whole path from raw text to a persisted structure, not
  only its final write step. Re-tokenizing on every repetition costs little compared to persisting (the
  part that actually differs across the three structures) and keeps each measured run fully
  self-contained, with nothing carried over between repetitions that could quietly bias later ones.
- **`benchmark_index_build` takes `books` as a plain `std::vector<SampleBook>` parameter, with no
  opinion on where they came from.** Exactly the same reasoning as `Datalake`/`IndexWriter` being
  interfaces the pipeline depends on rather than concrete choices: this function can be tested today
  with a two-book fixture, and pointed at `sample_dataset/`, at `cpp/data/datalake/`, or at anything
  else later, without changing a line of it.
- **Each structure writes under its own subdirectory of `output_dir`** (`monolithic/`, `hierarchical/`),
  so a single benchmark run's three structures never collide on the same path, and the output can be
  inspected structure by structure afterward.

---

## Entry 28 – index_query: generalizing query_and to compare structures fairly (2026-09-30)

### What was done
- `query_and` generalized: the core now takes a `std::function<std::vector<int>(const std::string&)>
  postings` instead of `const InvertedIndex&` directly, keeping the exact same de-duplicate/smallest-
  first/`std::set_intersection` algorithm from Entry 22 unchanged. The original signature survives as a
  one-line convenience overload (`query_and(index, terms)` delegates to the generic core with a lambda
  wrapping `index.postings`), so every existing caller and test needed no changes.
- `load_queries` (`query_list.hpp`/`.cpp`): reads `shared/queries.txt` in file order, skipping blank/`#`
  lines -- the same shape as `load_stopwords`/`load_book_ids`, now for the query workload SPEC section 1
  reserves that file for.
- `mongo_postings_fetcher(uri)` added next to `MongoIndexWriter`: returns a postings-fetcher backed by a
  live MongoDB connection (one `find_one` per term). The `mongocxx::client` is held through a
  `std::shared_ptr`, not by value, because `mongocxx::client` is move-only and a `std::function`'s
  target must be copy-constructible.
- `include/stage1/index_query_benchmark.hpp` + `src/index_query_benchmark.cpp`:
  `benchmark_index_query(language, dataset_size, queries, stopwords, index_dir)`, SPEC section 9's
  `index_query` experiment. For each structure `benchmark_index_build` already wrote — monolithic
  (parses the JSON file once, then answers every query from the parsed map), hierarchical (no upfront
  load: each query term opens and reads its own small file on demand), and mongo if reachable (one
  network round trip per term) — times "load the structure, then run every query in the workload" as
  one unit, `measure_elapsed_ms`'s default 2+5 repetitions, and returns one `BenchmarkResult` row per
  measured run.
- 6 new tests: `load_queries` (order, comments/blanks, missing file, the real `shared/queries.txt`) and
  `benchmark_index_query` (five rows per available structure with the right shape, mongo rows present
  only when reachable), built on top of `benchmark_index_build`'s output. Suite total: 126 tests.

### Why
- **Generalizing `query_and` instead of duplicating its intersection logic per structure.** Querying
  the monolithic JSON, the hierarchical files, and MongoDB each fetch postings a completely different
  way, but AND-intersecting whatever they fetch is the exact same algorithm every time; writing that
  algorithm three more times (once per structure) would triple the chance of a subtle bug (wrong sort
  order, a missed short-circuit) appearing in only one of the four copies. This is the same "generalize
  once a second real caller needs it" reasoning already applied to `HttpClient`, `TempDir`, and
  `mongo_is_reachable` — except here the second caller changed an existing function's *signature*
  rather than adding a sibling, which is why the old call shape was kept as a convenience overload
  instead of forcing every existing caller to wrap `index.postings` in a lambda by hand.
- **Querying each on-disk/database structure directly, not through the in-memory `InvertedIndex`.**
  This project's `InvertedIndex` is a single, shared, structure-agnostic representation — querying it
  would give the exact same timing for all three structures, since none of them would actually be
  involved. That would defeat the purpose of an experiment whose entire point, per SPEC section 6 and
  the course PDF, is comparing how these three physical structures perform.
- **The monolithic reader parses the file once and reuses it for the whole query batch; the
  hierarchical reader has no such step and pays a file access per query term instead.** This mirrors
  each structure's real shape: a monolithic file is naturally something a query service loads once and
  serves many queries against, while the hierarchical layout's whole design is "each term is its own
  file" (SPEC section 6) — there is nothing sensible to "load upfront" for it. Measuring both fairly,
  the same way, would hide exactly the trade-off SPEC asks the report to discuss.
- **"Load + whole query batch" timed as one combined unit, not load and per-query costs measured
  separately.** Keeping the measured operation identical in shape across all three structures (and
  identical to how `benchmark_index_build` already times "build + persist" as one unit) is what makes
  the three numbers directly comparable; splitting load from query cost would need a second,
  structure-specific methodology decision for each format, not a clear win worth the added complexity
  at this stage. Documented explicitly, including that this "cold" measurement does not reflect a real
  service that keeps a structure loaded across many queries — a defensible simplification, not a hidden
  one.
- **`mongo_postings_fetcher` returns a `std::function`, not a class implementing some `PostingsSource`
  interface.** Only one thing (this benchmark, so far) needs "a callable that fetches postings from
  somewhere"; introducing a new interface hierarchy for that, mirroring `Datalake`/`IndexWriter`, would
  be the same premature-generalization mistake already avoided once for `MetadataRepository` (Entry 13)
  — `std::function` is already the right amount of abstraction `query_and`'s own signature needed.

---

## Entry 29 – Real books instead of synthetic data, and a CLI to run benchmarks (2026-10-01)

### What was done
- `SampleBook` moved out of `index_build_benchmark.hpp` into its own `include/stage1/sample_books.hpp`
  (+ `src/sample_books.cpp`), since it is a corpus concept shared by every future experiment, not
  something that belongs to `index_build` specifically.
- `load_sample_books(candidate_ids, downloaded, metadata)`: walks `candidate_ids` in order, keeps only
  the ones `downloaded` already has recorded, and reads each one's real body straight off disk via the
  path `metadata` stored for it — the exact same lookup `main.cpp` already does to rebuild the in-memory
  index on startup (Entry 25). No network, no synthetic text: this reuses whatever a real
  `pipeline <N>` run already downloaded.
- `main.cpp` gained a second command: `search_engine_stage1 benchmark <index_build|index_query>`.
  Loads the real downloaded books via `load_sample_books`, runs the requested experiment, and writes
  `benchmarks/results/cpp_<experiment>.csv`. `index_query` first (re)builds the structures, untimed, so
  it always queries whatever `books` currently holds regardless of invocation order. Mirrors the Java
  module's own `benchmarks/work/` (scratch, git-ignored) vs `benchmarks/results/*.csv` (committed)
  split; `.gitignore` updated with the matching exception for `cpp/`, since the existing blanket `*.csv`
  rule would otherwise silently swallow these too (the same rule Daniel's Python branch added, and the
  same fix Java's own merge already applied for its own path).
- 3 new tests (`tests/sample_books_test.cpp`): only downloaded books are loaded with their real body
  content, nothing is downloaded means nothing is loaded, and candidate order is preserved regardless
  of insertion order into the control log. Suite total: 129 tests.
- **Ran it for real**, end to end, against this machine's actual network: `pipeline 30` downloaded and
  indexed all 15 books currently in `shared/book_ids.txt`; `benchmark index_build` and
  `benchmark index_query` then produced real CSVs (Mongo skipped, no Docker on this machine). Scratch
  `data/`/`benchmarks/work/` removed afterward; the two result CSVs were kept and committed.

### A genuinely useful early result
At `dataset_size=15`: **hierarchical took ~23x longer to build** than monolithic (≈1900ms vs ≈83ms,
averaged over 5 runs) — the cost of writing thousands of tiny per-term files that SPEC section 6 itself
calls out as hierarchical's weakness. But **hierarchical answered the query workload ~55x faster**
(≈0.25ms vs ≈14ms) — monolithic's measured time is dominated by re-parsing the whole JSON file on every
repetition (Entry 28's "cold" load), while hierarchical has no such step and just opens the handful of
small files each query actually needs. Exactly the kind of build-speed-vs-query-speed trade-off SPEC
section 6 and the course PDF ask the report to discuss, visible already with a 15-book sample — though
these specific numbers will need to be regenerated once the dataset is larger (see below).

### Why
- **Real text over synthetic generation**, unlike the Java module's `BenchmarkBooks.synthetic(...)`
  approach. This project already had a working, tested downloader (`GutenbergSource`/`CurlHttpClient`,
  Entry 9-10) and a pipeline that exercises it end to end (Entry 25); reusing real, already-downloaded
  books costs no new "fake text" generation code and gives real vocabulary and sentence-length
  distributions instead of a fixed, repeated word list. The trade-off, made explicit: it needs a one-time
  network step (`pipeline <N>`) before any benchmark can run, where synthetic data would not.
- **`SampleBook` relocated instead of left in `index_build_benchmark.hpp`.** `index_query`, and every
  future experiment that needs real books (`datalake_write`, `metadata_insert`, ...), would otherwise
  have had to `#include` the build benchmark's header just to get a type that has nothing to do with
  building anything — the same "this concept now has more than one real user" signal that already moved
  `TempDir`, `FakeHttpClient`/`FakeClock`, and `mongo_is_reachable` into shared locations.
- **`load_sample_books` silently skips a downloaded id with no metadata row**, instead of throwing.
  SPEC's own control-layer discipline (Entry 23-24: never mark something done until it fully succeeded)
  already guarantees this should not happen in practice; treating it as a hard error here would turn a
  pipeline implementation detail into a benchmark-tool crash, for no benefit over simply not counting
  that one book.
- **`benchmark index_query` rebuilds the structures itself, untimed, instead of assuming
  `benchmark index_build` already ran in the same invocation.** Each CLI command is self-sufficient:
  running `benchmark index_query` alone, days after the last `benchmark index_build`, still measures
  against the current contents of `books`, not stale files left over from an earlier, possibly different
  dataset size.
- **Small dataset (15 books) acknowledged explicitly, not hidden.** This is real, honest data — not a
  placeholder — but it is small enough that filesystem/OS caching effects could matter more than they
  would at the "hundreds/thousands" scale `shared/book_ids.txt`'s own comment calls for; the committed
  CSVs should be treated as an early sanity check of the benchmarking tools working correctly end to
  end, not as the final numbers for the report.

---

## Entry 30 – datalake_write, and SampleBook gets a header (2026-10-01)

### What was done
- `SampleBook` extended with a `header` field, appended last (not between `book_id` and `body`) so
  every existing two-value aggregate-init literal (`{1, "some body"}`, used by several tests) keeps
  meaning exactly what it did, with `header` simply defaulting to `""`. `load_sample_books` now reads
  both `body_path` and `header_path` from the stored metadata.
- `include/stage1/datalake_write_benchmark.hpp` + `src/datalake_write_benchmark.cpp`:
  `benchmark_datalake_write(language, books, output_dir)`, SPEC section 9's `datalake_write`
  experiment (section 3's own "download and write throughput"). For each of the three required
  layouts — `book`, `range`, `time` — times writing every book in `books` (header + body) through a
  fresh instance of that `Datalake`, `measure_elapsed_ms`'s default 2+5 repetitions, one
  `BenchmarkResult` row per measured run.
- `main.cpp`'s `benchmark` command gained `datalake_write` alongside `index_build`/`index_query`.
- 2 new tests (five rows per structure with the right shape; the written files actually exist at each
  layout's expected path, including a direct check against `time_folder_name(now())` for the time
  layout). Suite total: 131 tests.
- **A real bug this caught:** extending `SampleBook` with `header` broke two of `sample_books_test.cpp`'s
  own fixtures, which had been passing placeholder strings (`"header.txt"`, `"h"`) as `header_path` —
  harmless while nothing read that path, a hard failure (`cannot open file for reading`) the moment
  `load_sample_books` started reading it for real. Fixed by writing real header files in those fixtures,
  mirroring what they already did for `body_path`.
- **Ran it for real** against the same 15 real, already-downloaded books as Entry 29.

### A result that is honest about its own limits
At `dataset_size=15`, all three structures wrote in roughly the same ~10-14ms, no structure clearly
faster. This is expected, not a bug: 15 books means `book` creates 15 directories, `range` only 3 (the
15 ids in `shared/book_ids.txt` happen to fall into 3 thousand-ranges), and `time` just 1 (everything
written in the same run lands in the same hour); at that scale, directory-creation cost on a local SSD
is close to noise. The structural difference SPEC section 3 asks about — many small directories vs few
large ones — only becomes visible at the "hundreds/thousands" scale the dataset is meant to reach.
Documented so this result is not mistaken for "the three layouts perform identically."

### Why
- **`header` appended last on `SampleBook`, not inserted after `book_id`.** Changing an existing
  struct's layout without breaking callers that used positional aggregate initialization is exactly the
  kind of small compatibility decision `tokenize`'s two-argument overload (Entry 6) and `query_and`'s
  generic core (Entry 28) already established a habit of making — grow the shape, do not reorder it.
- **Each repetition re-writes every book, not just the first one.** Same reasoning as
  `benchmark_index_build` (Entry 27): every repetition is fully self-contained, and `Datalake::write`
  already overwrites rather than appends (Entry 14), so repeating the same writes 7 times measures the
  same "steady state" cost each time, with nothing left over from a previous repetition to bias the next.
- **Three separate `Datalake` instances (one per layout), not one function switching on a `structure`
  string.** This mirrors `benchmark_index_build`'s own shape, and keeps each layout's real constructor
  (including `TimeBasedDatalake`'s `Clock&`) explicit at the call site rather than hidden behind a
  string-based dispatch that would need its own tests to get right.
- **The small-dataset result reported honestly, with the reason written down, instead of silently
  omitted or re-run until it "looked better."** The point of these early runs (Entry 29 already flagged
  this) is to confirm the tools work correctly end to end; a flat result here is real evidence the
  write-cost difference needs a larger dataset to appear, which is itself useful information for the
  report, not a failure to hide.

---

## Entry 31 – datalake_lookup: giving Datalake a locate() method (2026-10-01)

### What was done
- `Datalake` interface gained `virtual std::optional<BookLocation> locate(int book_id) const = 0;`,
  symmetric to `write()`. Each layout now implements it:
  - `BookBasedDatalake`/`RangeBasedDatalake`: `locate` recomputes the path (pure function of the id,
    same as `write` always did) and checks the files exist with `std::filesystem::exists`; works from
    any instance, even a brand new one on the same root (a new test confirms this explicitly).
  - `TimeBasedDatalake`: the path depends on *when* a book was written, not just its id, so there is no
    formula to invert. It now keeps an internal `std::unordered_map<int, BookLocation> written_`,
    populated by `write()`; `locate()` only ever finds books the *same instance* wrote. A new test
    (`LocateReturnsNulloptFromAFreshInstanceEvenIfTheFileExists`) pins this down: a second
    `TimeBasedDatalake` on the same root, after the first one already wrote the file for real, still
    returns `nullopt` — the file is there, but nothing remembers where.
  - `BookBasedDatalake`/`RangeBasedDatalake::write` refactored to share path computation with `locate`
    through a private `paths_for(book_id)` helper, so the two methods cannot silently drift apart.
- `include/stage1/datalake_lookup_benchmark.hpp` + `src/datalake_lookup_benchmark.cpp`:
  `benchmark_datalake_lookup(language, books, output_dir)`, SPEC section 9's `datalake_lookup`
  experiment (section 3's own "lookup cost"). For each layout: writes every book once, untimed, then
  times calling `locate()` for every book id, `measure_elapsed_ms`'s default 2+5 repetitions.
- `main.cpp`'s `benchmark` command gained `datalake_lookup`.
- 7 new tests (6 `locate()` tests across the three datalake test files, 1 for the benchmark itself).
  Suite total: 138 tests.
- Ran it for real against the same 15 downloaded books.

### An honest limitation this result exposes
At `dataset_size=15`: `time` locates in ≈0.001ms, `book`/`range` in ≈0.04ms — `time` looks *faster*,
which is the opposite of the real-world disadvantage already discussed before writing any code for this
phase ("time no puede calcular la ruta solo con el ID"). The reason is specific to how this benchmark is
built: it writes and locates through the *same* `Datalake` instance within one process, so `time`'s
lookup is a bare in-memory hash map hit, while `book`/`range` each pay a real filesystem `stat()` call.
This experiment, as built, cannot show `time`'s real weakness — a fresh process (like a restarted
pipeline) that has forgotten everything and must fall back to an external index (`MetadataStore`,
exactly what `main.cpp`'s own pipeline already does) to find anything at all. Documented explicitly in
the header and here, rather than left to look like "time turned out to be the fastest layout."

### Why
- **`locate()` added to the existing `Datalake` interface instead of a free function per layout.**
  Mirrors `write()`'s own shape (one virtual method, one concrete implementation per layout) and lets
  `benchmark_datalake_lookup` work through `Datalake&` uniformly, the same reasoning `IndexWriter`
  already established for `benchmark_index_build`.
- **`TimeBasedDatalake`'s `written_` map is the honest way to implement "find what I wrote," not a
  shortcut.** It cannot do better: nothing about `book_id` encodes *when* it was written, and
  SPEC/the PDF explicitly frame this inability to compute the path as the trade-off worth measuring.
  Keeping the map (rather than, say, always returning `nullopt`) makes `locate()` still usefully
  correct within one running process — just not across a restart, which is the precise, narrow gap this
  entry documents rather than papers over.
- **`paths_for()` extracted in `BookBasedDatalake`/`RangeBasedDatalake`.** `write()` and `locate()` must
  agree on exactly the same path for the same id; computing it in one place removes any chance of the
  two methods disagreeing after a future edit to either.
- **Writing happens untimed, before the measured block.** This experiment is specifically about lookup
  cost, not write cost (already covered by `datalake_write`, Entry 30); mixing the two into one timed
  block would make this experiment redundant with that one instead of measuring something new.

---

## Entry 32 – Cross-language comparison: how each language solved TimeBasedDatalake's lookup (2026-10-01)

### What was found
Comparing `locate()`'s implementation across the three language modules, each independently hit the
exact same design problem flagged in Entry 31 (a time-based path cannot be computed from the id alone)
and solved it differently:
- **C++ (this module):** an in-memory `std::unordered_map<int, BookLocation>` kept by `TimeBasedDatalake`
  itself, populated by `write()`. Fast (O(1)), but only ever finds books `write()`'s own instance has
  seen — a fresh instance (e.g. after a restart) finds nothing, even for files genuinely on disk.
- **Java:** no memory at all. `TimeBasedDatalake.locate()` walks every `YYYYMMDD/HH` subdirectory
  (newest first) and checks each one for the id's files, giving up only once every folder has been
  tried. Its own comment states the reasoning in the same words this project's DEVLOG already used:
  *"La ruta NO se puede calcular a partir del id, así que locate tiene que buscar."* Survives a restart
  (nothing to forget), at the cost of scanning more folders as the dataset grows.
- **Python:** no `locate`/lookup of any kind yet for any layout, only the write path
  (`save_time_based`). `datalake_lookup` has not been implemented there yet.

### Why this is worth recording
Three independent implementations of the same SPEC requirement arrived at the same conclusion about
*why* `time` is the hard case, and then made genuinely different, opposite-tradeoff choices for how to
handle it — memory-bound-but-amnesiac (C++) versus disk-scan-but-durable (Java). That contrast is
exactly the kind of cross-language design discussion the final report's "design decisions" and
"benchmarks and results" sections are supposed to contain, and it was found by reading a teammate's
code after a direct question, not by planning for it in advance — worth remembering to check teammates'
equivalent code when a design problem feels like it should be universal, not C++-specific.

---

## Entry 33 – datalake_incremental: Datalake gets list_book_ids() too (2026-10-01)

### What was done
- `include/stage1/file_io.hpp`/`.cpp`: `collect_body_header_pairs(dir, ids)`, a shared helper that
  scans one directory for `"<id>.body.txt"` files with a matching `"<id>.header.txt"` sibling, parsing
  the id with `std::from_chars` (same style as `ControlLog`/`load_book_ids`). Used by both
  `RangeBasedDatalake` and `TimeBasedDatalake`, which only differ in how many directory levels they
  walk before reaching files named this way.
- `Datalake` interface gained `virtual std::vector<int> list_book_ids() const = 0;`, implemented by all
  three layouts by walking their own directory tree (never by remembering past writes — unlike
  `locate()` for `time`, Entry 31/32, this needs no bookkeeping and works from a brand new instance,
  confirmed by a dedicated test for each layout).
- `include/stage1/datalake_incremental_benchmark.hpp` + `src/datalake_incremental_benchmark.cpp`:
  `benchmark_datalake_incremental(language, books, output_dir)`, SPEC section 9's `datalake_incremental`
  experiment. **Methodology deliberately mirrors the Java module's own `DatalakeBenchmark.incremental`**
  (found by reading its code, see Entry 32): the most recent 10% of `books` (at least one) are treated
  as "fresh", the rest as already "known"; both get written (untimed setup), then each layout is timed
  calling `list_book_ids()` and subtracting the known ids, `measure_elapsed_ms`'s default 2+5
  repetitions. Every repetition's detected set is checked against the real fresh ids and throws if it
  ever disagrees, the same correctness guard Java's version has.
- `main.cpp`'s `benchmark` command gained `datalake_incremental`.
- 7 new tests (3 `list_book_ids()` tests across the three datalake test files, 2 for the benchmark
  itself, plus the two `list_book_ids` tests). Suite total: 144 tests.
- Ran it for real against the 15 downloaded books (10 cmd correctness checks all passed silently, no
  thrown mismatch); all three layouts came out close and sub-millisecond at this small scale.

### Why
- **Matching Java's 90/10 methodology instead of inventing our own.** SPEC section 9 only names the
  experiment; it does not fix how "new" books are simulated. Reading a teammate's already-working
  implementation and reusing its exact split (rather than, say, picking a different percentage or
  simulating "new" differently) is what makes the resulting CSVs directly comparable across languages
  in the report, which is the whole stated purpose of the shared CSV format in the first place.
- **`list_book_ids()` added to `Datalake`, not computed by reading the control layer's own files.**
  SPEC section 3 frames incremental detection as a property of the datalake *layout itself*
  ("later stages... can focus only on the most recent folders instead of scanning the entire
  datalake"), independent of whatever external bookkeeping a control layer happens to keep; using
  `ControlLog` here instead would have measured `ControlLog`'s hash-set performance (already
  known-cheap, Entry 23) rather than anything specific to `book`/`range`/`time`, and would have given
  every layout an identical, uninteresting result.
- **`collect_body_header_pairs` factored out rather than duplicated between `range` and `time`.** Both
  layouts store files the same way (`"<id>.body.txt"`/`"<id>.header.txt"` directly inside a folder);
  only how many folders deep they walk to reach one differs. Sharing the filename-parsing logic means a
  future bug fix (e.g. a malformed filename edge case) only needs to happen once.
- **The detected set is checked for correctness on every repetition, not just assumed.** A subtly wrong
  `list_book_ids()` (e.g. missing a layout's deepest directory level) would otherwise produce a
  plausible-looking but meaningless timing number instead of a visible failure — the same reasoning
  Java's own `require(...)` check already applied.

---

## Entry 34 – datalake_recovery, and a generalized measure_elapsed_ms (2026-10-01)

### What was done
- `measure_elapsed_ms` gained a `(setup, operation, warmup_runs, measured_runs)` overload: `setup()`
  runs untimed before every single repetition (both warmup and measured), then `operation()` is timed.
  The existing single-argument shape survives as a one-line convenience overload delegating to it with
  an empty `setup` — no existing call site needed to change, same "grow the shape, keep the old call
  working" habit as `tokenize`'s stopword overload (Entry 6) and `query_and`'s generic core (Entry 28).
  2 new tests confirm `setup` runs once per repetition (not once overall) and that its state is visible
  to `operation` on each call.
- `collect_body_header_pairs`/`list_book_ids` (Entry 33) reused; `count_files_with_suffix(dir, suffix)`
  added to `file_io.hpp`/`.cpp`: recursively counts files under `dir` whose name ends with `suffix`
  (`"body.txt"` matches both `book`'s exact filename and `range`/`time`'s `"<id>.body.txt"`).
- `include/stage1/datalake_recovery_benchmark.hpp` + `src/datalake_recovery_benchmark.cpp`:
  `benchmark_datalake_recovery(language, books, output_dir)`, SPEC section 9's `datalake_recovery`
  experiment, **mirroring the Java module's own "damage every 10th book" methodology** (same reasoning
  as `datalake_incremental`, Entry 33, for comparable cross-language CSVs). Per structure: `setup()`
  (untimed, runs before every repetition) clears the directory, writes every book, then deletes the
  *header* file of every 10th book -- simulating a crash between `write_text_file`'s two separate calls
  for body and header (this project has no atomic write, unlike Java's, see Entry 32's cross-language
  comparison style). The timed `operation()` is "list what's present, rewrite whatever's missing."
  After the loop, the function itself verifies (and throws if not true) that every book is present and
  no structure has more `body.txt` files than books written — the same correctness guard Java's
  `require(...)` makes. Returns 5 `elapsed` rows plus one `recovered` and one `duplicates` row per
  structure.
- `main.cpp`'s `benchmark` command gained `datalake_recovery`.
- 4 new tests (2 for the new `measure_elapsed_ms` overload, 2 for the benchmark itself). Suite total:
  148 tests.
- Ran it for real against the 15 downloaded books (damages book index 10, the only "every 10th" with 15
  books): all three structures recovered the one damaged book with zero lost books and zero duplicates.

### Why
- **A generic `setup`/`operation` split in `measure_elapsed_ms`, not a bespoke loop inside this one
  benchmark.** Recovery is the first experiment where repeating the same operation seven times is not
  automatically equivalent work (once recovered, there is nothing left to recover) — every earlier
  experiment (`index_build`, `datalake_write`, ...) sidestepped this by rebuilding everything from
  scratch each repetition, which recovery cannot do without re-damaging first. The split is written as
  a reusable addition to the shared benchmark infrastructure (Entry 26) because `index_update`, still
  to come, has the same shape (a pre-built index, then timing one incremental change), not as a
  one-off local loop only `datalake_recovery` could use.
- **Damage simulated by deleting the header after a normal write, not via a temp-file/rename atomic
  write like Java's.** This project's `write_text_file`/`Datalake::write` genuinely write body and
  header as two separate, non-atomic steps; simulating the crash this way tests *this* codebase's real
  failure mode, rather than importing an atomic-write mechanism this project does not have just to copy
  Java's specific technique. (Whether to add atomic writes here is a separate, open design question the
  benchmark result does not answer by itself.)
- **Damage and recovery methodology matched to Java's, not invented independently.** Same reasoning as
  Entry 33: SPEC only names the experiment, not its exact simulated-failure shape; reusing the already-
  working "every 10th book" sample keeps the resulting `recovered`/`duplicates` numbers meaningfully
  comparable across the three language implementations in the report.
- **The function throws on any lost book or duplicate instead of just reporting whatever it measures.**
  A recovery experiment whose entire point is correctness should fail loudly the moment it is not
  correct, rather than silently writing a CSV row that looks like a timing result but actually hides a
  bug in `write`/`locate`/`list_book_ids` working together.

---

## Entry 35 – datalake_storage, closing the datalake benchmark block (2026-10-01)

### What was done
- `include/stage1/datalake_storage_benchmark.hpp` + `src/datalake_storage_benchmark.cpp`:
  `benchmark_datalake_storage(language, books, output_dir)`, SPEC section 9's `datalake_storage`
  experiment (section 3's own "storage overhead"). Not a timing experiment: for each layout, writes
  every book once, then reports `files`, `directories`, `max_entries_per_dir` (the most populated
  directory, root included) and `bytes` (summed logical file size) via a single recursive directory
  walk (`std::filesystem::recursive_directory_iterator`), one `BenchmarkResult` row per metric.
  Deliberately leaves out the Java module's fifth metric, block-size-rounded `allocated_bytes`: there is
  no portable C++ standard-library way to query a filesystem's block size, and Java's own code already
  calls that number "an estimate" rather than an exact figure.
- `main.cpp`'s `benchmark` command gained `datalake_storage`. This closes all five of SPEC section 9's
  `datalake_*` experiments.
- 3 new tests with a small, hand-traceable 3-book corpus (1342, 84, 11): `book` gets exactly 2
  files/1 directory per book; all three layouts report identical `bytes` (same content, different
  organization); `range` groups 84 and 11 into one folder (`00000-00999`) and 1342 into another
  (`01000-01999`). Suite total: 151 tests.
- Ran it for real against the 15 downloaded books.

### A result that makes the book/range/time trade-off concrete
All three layouts: 30 files, identical byte count (6,575,252 — same content, just organized
differently; a useful sanity check in itself). Where they differ is `max_entries_per_dir`:
`book` = 15 (the root directory, one subdirectory per book), `range` = 18 (the most populated range
folder, `00000-00999`, holding 9 of the 15 books), `time` = **30** — every single file, because all 15
books were downloaded within the same hour and `time` has no way to spread a burst of downloads across
multiple folders. This is precisely the failure mode SPEC section 3 warns about ("a very large number
of small files can overwhelm the filesystem") showing up with real numbers, and it is `time`'s second
documented structural weakness in this project (after `locate()`'s inability to compute a path from the
id alone, Entry 31) — both stemming from the same root cause: `time`'s organization depends on *when*
things happen to be written, which this project's own pipeline does in bursts, not evenly.

### Why
- **One directory walk per structure, not per metric.** `files`, `directories`, `max_entries_per_dir`
  and `bytes` all fall out of the same single pass over every entry under the root; walking the tree
  four separate times (once per metric) would be needlessly repeated I/O for numbers that are all
  byproducts of the same traversal.
- **`entries_per_dir` keyed by `parent_path()`, counting the root's own direct children too.** Matches
  the Java module's own explicit choice ("incluida la raíz"): for the `book` layout specifically, the
  root directory (one subdirectory per book) is very often the most populated directory in the whole
  tree, and excluding it would hide exactly the kind of overcrowding this metric exists to catch.
- **`allocated_bytes` left out rather than approximated with a guessed block size.** A hardcoded
  assumption (e.g. "assume 4096-byte blocks") would silently misreport on a filesystem that does not
  use that block size, which is worse than not reporting the number at all; Java's own version, built
  with `Files.getFileStore(root).getBlockSize()`, has no equivalent in portable C++ without reaching for
  platform-specific APis (`statvfs` on POSIX, `GetDiskFreeSpace` on Windows) this project has not needed
  anywhere else — a reasonable line to draw given `bytes` (logical size) already answers "how much data"
  and the point of `max_entries_per_dir` already covers the structural overcrowding concern.

---

## Entry 36 – metadata_insert, and a transaction gap this benchmark exposed (2026-10-01)

### What was done
- `include/stage1/metadata_insert_benchmark.hpp` + `src/metadata_insert_benchmark.cpp`:
  `benchmark_metadata_insert(language, books, output_dir)`, SPEC section 9's `metadata_insert`
  experiment (section 4's own "insertion speed"). Before each repetition (untimed, via
  `measure_elapsed_ms`'s `setup`): deletes any previous database file and opens a fresh
  `MetadataStore` (schema creation happens here, not in the timed part). Timed: `extract_metadata`
  each book's header and `insert_book` it. Verifies every book is present via `find_by_id` after the
  final repetition. `structure` is always `"sqlite"` — this project still has only one metadata
  backend (Entry 13's reasoning for not generalizing `MetadataStore` without a second real
  implementation still holds), unlike the Java module, which also benchmarks a `"sqlite_no_index"`
  variant by dropping the author/title indexes. Returns 5 `elapsed` rows plus 5 derived `throughput`
  rows (`rows_per_s`), matching the Java module's own metric shape.
- `main.cpp`'s `benchmark` command gained `metadata_insert`.
- 1 new test. Suite total: 152 tests.
- Ran it for real against the 15 downloaded books' real headers.

### A real gap this exposed: no transaction batching
At `dataset_size=15`: throughput varied noisily between roughly 1,260 and 2,130 rows/s across the 5
measured repetitions — noisy for a reason worth naming: `MetadataStore::insert_book` runs each `INSERT`
as its own implicit SQLite transaction (no `BEGIN`/`COMMIT` wrapping multiple rows), so every single row
pays its own commit cost. The Java module's equivalent explicitly batches rows into one transaction per
batch (`saveAll`, `DEFAULT_BATCH_SIZE = 1000`). This project's current `MetadataStore` does not offer
that at all. Not fixed here — this benchmark's job is to measure and report what exists, not to
silently patch the thing being measured — but recorded as a concrete, benchmark-discovered candidate
improvement: wrapping a batch of inserts in one transaction would very likely raise and stabilize this
throughput number, and is exactly the kind of finding SPEC section 4's benchmarking considerations
("insertion speed... thousands of books") exist to surface before the dataset is large enough for the
per-row commit cost to dominate badly.

### Why
- **Opening the database (and creating its schema) happens in `setup`, not inside the timed
  operation.** That cost is fixed and one-time per repetition regardless of how many rows get
  inserted; including it in the timed block would inflate "insertion speed" by a cost that has nothing
  to do with how many rows were inserted, especially visible at this project's current small sample
  sizes.
- **`structure` fixed to `"sqlite"` rather than inventing a second backend to compare.** Same reasoning
  already applied in Entry 13: SPEC does not require this comparison (it is explicitly optional, per
  the course PDF), and building a toggle for it now, with no immediate plan to add a second backend,
  would be exactly the premature generalization that reasoning was written to avoid.
- **Verifying every book is findable after the run, not just trusting the loop completed.** The same
  "a benchmark about correctness should fail loudly if it is not correct" reasoning already applied to
  `datalake_incremental`/`datalake_recovery` (Entries 33-34): a silently-broken `insert_book` would
  otherwise still produce a plausible-looking timing number.
- **The missing-transaction finding recorded rather than silently fixed.** Changing `MetadataStore` to
  batch inserts is a real, separate design decision (how big a batch, whether to expose it as part of
  the public `MetadataStore` API or only used internally by callers that know they are doing bulk
  work) that deserves its own deliberate step, not a quick patch made only because a benchmark run
  happened to reveal it.

---

## Entry 37 – Fixing the transaction gap Entry 36 found, and measuring the difference (2026-10-01)

### What was done
- `MetadataStore` gained `begin_transaction()`/`commit_transaction()`/`rollback_transaction()`
  (`BEGIN TRANSACTION;`/`COMMIT;`/`ROLLBACK;`, reusing the existing `exec` helper). 2 new tests:
  inserts made between `begin_transaction()` and `commit_transaction()` are visible after commit;
  `rollback_transaction()` discards everything written since `begin_transaction()`, leaving anything
  committed *before* it untouched.
- `benchmark_metadata_insert`'s timed operation now wraps its whole insert loop in one transaction
  (`begin_transaction()` ... `commit_transaction()`) instead of leaving every `insert_book()` call to
  commit on its own. Suite total: 154 tests.
- **Re-ran the real benchmark against the same 15 downloaded books, before and after, to measure the
  actual difference** rather than assuming the fix helped:

  | | elapsed (ms, 5 runs) | throughput (rows/s, 5 runs) |
  |---|---|---|
  | **Before** (Entry 36, no transaction) | 11.3, 7.6, 11.9, 7.0, 7.3 | 1326, 1965, 1263, 2131, 2051 |
  | **After** (this entry, one transaction) | 3.3, 3.3, 3.2, 3.3, 3.3 | 4485, 4506, 4638, 4557, 4614 |

  Roughly **2.5x faster on average**, and just as importantly, the measured time went from noisy
  (7.0-11.9ms, swinging by a factor of ~1.7x run to run) to tightly consistent (3.2-3.3ms every time).
  The noise itself is explained by what changed: 15 separate implicit commits (each paying its own,
  somewhat variable fsync cost) became 1 commit for the whole batch.

### Why
- **Measured before and after with the same benchmark, not just reasoned that it should help.** The
  whole point of building this benchmarking infrastructure (Entry 26 onward) is to replace "this should
  be faster" with an actual number; fixing the gap Entry 36 found without re-running the same experiment
  would have left the claim unverified, exactly the kind of thing this project's benchmarks exist to
  avoid.
- **`begin_transaction`/`commit_transaction`/`rollback_transaction` added as general `MetadataStore`
  methods, not hidden inside the benchmark.** Transaction batching is useful to any future caller doing
  bulk inserts (the real pipeline's indexing step currently inserts one book at a time, which does not
  need this, but a future bulk-import path would); exposing it on `MetadataStore` itself, the same place
  `insert_book` already lives, means the benchmark is just an ordinary caller of a real feature, not a
  special case with its own private workaround.
- **`rollback_transaction` included even though nothing calls it yet.** A minimal, complete
  begin/commit/rollback surface costs one more `exec` call and avoids leaving an odd, asymmetric API
  (commit with no way to abort) now that the mechanism exists at all; the dedicated rollback test
  exists specifically so this is not an untested, unverified method sitting in the codebase.

---

## Entry 38 – metadata_query, and two query methods the indexes were waiting for (2026-10-01)

### What was done
- `MetadataStore` gained `find_by_author(author)`/`find_by_title(title)` (exact match, not substring),
  returning every matching `StoredBook`. Both reuse a new shared `read_row`/`find_all` pair of internal
  helpers (anonymous namespace), and `find_by_id` was refactored to use the same `read_row` instead of
  duplicating the column-reading code. 4 new tests: multiple matches, no matches, matches share a title
  across different authors, and exact-match semantics (`"Jane"` does not match `"Jane Austen"`).
  **This closes a real gap**: the author/title SQLite indexes (`idx_books_author`/`idx_books_title`)
  have existed since Entry 12, created for exactly this kind of lookup, but nothing in this project ever
  called a query that would use them until now.
- `include/stage1/metadata_query_benchmark.hpp` + `src/metadata_query_benchmark.cpp`:
  `benchmark_metadata_query(language, books, output_dir, query_count=1000)`, SPEC section 9's
  `metadata_query` experiment (section 4's own "query performance": "Find all books by a specific
  author; or Retrieve the path of a book by its title or ID"). Mirrors the Java module's own
  methodology: the database is populated once (untimed, via the transaction batching from Entry 37); a
  fixed-seed (`std::mt19937(42)`) workload of `query_count` random picks (repeats allowed) feeds three
  query types -- `find_by_id`, `find_by_author`, `find_by_title` -- each run as one timed block of the
  whole workload per repetition, reporting both the total `elapsed` and a derived `<type>_avg`
  (microseconds per single lookup). Every query must find at least one result (verified, throws
  otherwise); every book must have both a title and an author, or the function throws immediately
  (the workloads need something to query for).
- `main.cpp`'s `benchmark` command gained `metadata_query`.
- 2 new tests for the benchmark itself. Suite total: 160 tests.
- Ran it for real against the 15 downloaded books' real metadata, 1000 queries per type: all three
  types landed around 8-9 microseconds per query on average (`find_by_id` slightly faster, ~8us, than
  the indexed-but-still-disk-backed `find_by_author`/`find_by_title`, ~9-10us) — close enough at this
  small dataset size that the gap is not yet meaningful; worth re-measuring once the dataset is larger,
  the same caveat already noted for every other benchmark run so far at `dataset_size=15`.

### Why
- **`find_by_author`/`find_by_title` added now, not earlier.** They had no caller until this
  experiment needed them; building them speculatively back in Phase 3 would have been exactly the kind
  of premature addition Entry 13 already argued against for a different part of `MetadataStore`. A
  benchmark that needs a real capability is precisely the "second real use" signal this project has
  used throughout (`HttpClient`, `TempDir`, `mongo_is_reachable`, `Datalake::locate`/`list_book_ids`) to
  decide when generalizing stops being speculative.
- **Exact match, not a substring/`LIKE` search.** SPEC section 4's own phrasing ("find all books by a
  specific author") describes looking up a known author, not a fuzzy search; exact match is simpler,
  faster (a plain index lookup rather than a table scan `LIKE` would often require), and is what the
  existing index actually accelerates.
- **The whole query-type workload timed as one block, not query-by-query.** Matches
  `benchmark_index_query`'s own reasoning (Entry 28): a single query is too fast to time meaningfully on
  its own (clock resolution and call overhead would dominate), so timing `query_count` of them together
  and deriving a per-query average is the way to get a stable, meaningful number.
- **The database populated via a transaction, not row-by-row.** Directly reuses the fix from Entry 37
  instead of reintroducing the same one-commit-per-row cost in a different benchmark's setup step; this
  experiment is about query cost, and an unnecessarily slow population phase would be noise in
  comparison, not signal.
- **Requiring every book to have a title and an author, throwing otherwise, instead of silently
  skipping incomplete books.** A workload built by skipping some books while keeping others changes
  `query_count`'s real size unpredictably and could silently shrink to "no author data at all" for
  (say) a corpus where titles extract cleanly but authors do not; failing loudly surfaces a header-
  parsing problem immediately rather than producing a quietly-smaller, misleading benchmark.

---

## Entry 39 – index_update reveals hierarchical is far worse than monolithic for updates (2026-10-01)

### What was done
- `include/stage1/index_update_benchmark.hpp` + `src/index_update_benchmark.cpp`:
  `benchmark_index_update(language, books, stopwords, output_dir)`, SPEC section 9's `index_update`
  experiment (the course PDF's "cost of adding new books to an existing index without rebuilding it
  completely"). Mirrors the Java module's own methodology: the most recent 10% of `books` (at least
  one, `k`) are "added"; the rest form a "base" index, built and persisted once per repetition
  (untimed setup, via `measure_elapsed_ms`'s `setup`). The timed operation adds the `k` books one at a
  time, each immediately followed by a full `IndexWriter::write()` call — currently the *only* kind of
  "update" any of this project's three writers support (Entry 18/25 already documented that `write()`
  always persists the whole current index, never incrementally). After the run, the final in-memory
  index is checked term-by-term against building straight from every book, the same correctness
  discipline as `datalake_incremental`/`datalake_recovery`. Returns 5 `elapsed` rows plus a derived
  `per_book` row (`elapsed / k`) per structure.
- `main.cpp`'s `benchmark` command gained `index_update`.
- 2 new tests (with a tiny synthetic vocabulary, so the real-world cost below does not show up there
  and the suite stays fast). Suite total: 162 tests.
- Ran it for real against the 15 downloaded books (`k=1`, so each repetition adds exactly one more
  real book to a 14-book base). Mongo skipped (no Docker on this machine).

### A genuinely surprising, measured result
`monolithic`: **~23-31ms** per update. `hierarchical`: **~3,180-4,560ms** per update — **over 100x
slower**, the opposite of the naive expectation (and the opposite of what the Java module's own design
achieves, per its comment: *"hierarchical sólo los ficheros de los términos del libro"*). The cause is
architectural, not a fluke: `HierarchicalIndexWriter::write()` (Entry 19) iterates over *every* term in
`index.entries()` and rewrites *all* of their files on every call, because it has no notion of "which
terms actually changed since the last write" — the same `write()` means "make the whole structure match
this index" contract every writer in this project shares (Entry 18). With a real book's vocabulary
running into the low thousands of distinct terms, one update means thousands of individual small-file
`write_text_file` calls (each its own `create_directories` check, open, write, close), while
`monolithic` pays for exactly one file write regardless of vocabulary size. This is the project's
clearest evidence yet (after Entries 31-35's smaller `time`-layout findings) that this stage's writers
were built to answer "can the format represent three physically different layouts correctly" (which
they do — proven by `index_build`/`index_query`'s correctness checks) rather than "is this layout
efficient to update," which SPEC section 9 keeps as a question to measure, not assume.

### Why
- **Not fixed here, same reasoning as Entry 36's transaction gap before Entry 37 fixed it.** This
  result exposes a real architectural limitation of `HierarchicalIndexWriter` specifically (it would
  need to track which terms a given `add_book` call actually touched, and write only those files,
  to behave the way SPEC section 6 frames the hierarchical layout's whole selling point — "very
  fine-grained updates: only the file of the affected term is modified"). That is a meaningfully larger
  change than adding a transaction call, and deserves its own deliberate step rather than a reactive
  patch inside a benchmark-writing session; recorded here as a concrete, numbers-backed candidate for
  future work instead.
- **Measuring with real book text instead of a tiny synthetic vocabulary is what made this visible at
  all.** The unit test's own tiny corpus (a couple of words per book) would never reveal this cost,
  because the whole problem scales with vocabulary size, not book count — exactly why Entry 29's
  decision to benchmark against real, already-downloaded books (not synthetic placeholder text) mattered
  here specifically, beyond the general realism argument already made there.
- **The correctness check still runs even though this entry is about timing, not correctness.** A
  writer that is slow but still produces the right answer is a performance finding to report; a writer
  that is slow *and* wrong would be a bug to fix first, and the two are easy to conflate without an
  explicit check separating them.

---

## Entry 40 – Fixing hierarchical's update cost (Entry 39), and what the fix did and did not solve (2026-10-01)

### What was done
- `IndexWriter` gained `update_terms(index, changed_terms)`: persists only the listed terms' current
  postings, leaving every other already-persisted term untouched. Defaults to `write(index)` (a full
  rewrite -- always correct, not necessarily cheap), so every existing writer keeps compiling and
  behaving exactly as before without an override.
- `HierarchicalIndexWriter::update_terms` overridden: for each term in `changed_terms`, rewrites only
  that term's own file -- exactly SPEC section 6's own description of this layout's advantage ("very
  fine-grained updates: only the file of the affected term is modified"), which nothing in this project
  actually exercised until now.
- `MongoIndexWriter::update_terms` overridden too, for completeness and because it is cheap and
  correct to add: `update_one` with `$set` and `upsert(true)` per changed term, instead of
  `delete_many` + reinserting everything. Not benchmarked for real here (no Docker on this machine),
  but has its own skippable test, same pattern as the rest of the Mongo-dependent tests.
- `MonolithicIndexWriter` left with no override, on purpose: a single JSON file has no way to patch
  part of itself cheaply with this project's plain-text approach, so the default (full rewrite) is
  already the honest, correct answer for this layout -- not a missed optimization.
- `benchmark_index_update`'s timed operation now computes the *distinct* terms of each newly added book
  (the only terms `add_book` could possibly have changed, Entry 17) and calls `writer.update_terms(index,
  changed_terms)` instead of `writer.write(index)`.
- 5 new tests: `HierarchicalIndexWriter::update_terms` touches only the requested terms' files and
  leaves everything else exactly as `write()` left it (and handles an empty term list); a skippable
  `MongoIndexWriter::update_terms` equivalent. Suite total: 165 tests.
- **Re-ran the real benchmark (same 15 books) before and after, to measure the actual effect:**

  | structure | per-update, before (Entry 39) | per-update, after (this entry) |
  |---|---|---|
  | monolithic | 23.4–31.3 ms | 25.2–28.0 ms (unchanged, as expected: no override) |
  | hierarchical | 3,178.8–4,561.7 ms | **983.3–1,169.7 ms** |

### An honest reading of the result: real improvement, not a full fix
`hierarchical` got **roughly 3-4x faster** per update, a genuine, measured win from the fix. But it is
still **~35-45x slower than `monolithic`** at this dataset size, not close to parity. The reason is
structural, not a remaining bug: `changed_terms` for one newly added real book is still that book's
*own* distinct vocabulary (plausibly a couple of thousand words), and each one still costs its own
`write_text_file` call — a `create_directories` check, an open, a write, a close. The fix removed the
waste of touching *every other book's* terms too, but it cannot remove the fact that a layout built
from "one small file per term" pays a per-term filesystem cost that "one JSON file" simply does not.
This is the more precise, measured version of the architectural trade-off SPEC section 6 already
describes in words ("A very large number of small files can overwhelm the filesystem, reducing
performance") — now with a before/after number attached to both the problem and the fix's real,
partial effect on it.

### Why
- **A new interface method with a safe default, not a breaking signature change to `write()`.** Every
  other benchmark and every existing test that calls `write()` needed zero changes; `update_terms` is
  purely additive, and a writer that does not override it is still fully correct (just not faster),
  which is exactly the same "grow the shape, never break an existing caller" discipline already applied
  to `tokenize`, `query_and`, and `Datalake`'s own `locate()`/`list_book_ids()` additions.
- **`MonolithicIndexWriter` deliberately left without an override.** Giving every writer a "pretend
  incremental" method by, say, having monolithic's default secretly still rewrite the whole file under
  a different method name would not be an optimization, just the same cost with a misleading name;
  leaving it on the honest default makes the real difference between layouts visible in the benchmark
  results instead of hidden behind an API that implies all three writers now update cheaply.
- **Re-measuring instead of assuming the fix worked, same discipline as Entry 37's transaction fix.**
  The obvious, appealing story ("hierarchical only touches what changed, so it must now be about as fast
  as monolithic") turned out to be wrong in degree, and only a real before/after run caught that. Writing
  this nuance into the DEVLOG rather than just the headline "3-4x faster" is what keeps this log useful
  for the report: both the improvement and its real limit are facts worth knowing before deciding
  whether this structure is good enough for the group's final choice.

---

## Entry 41 – index_memory, measured without a garbage collector to lean on (2026-10-01)

### What was done
- `include/stage1/index_memory_benchmark.hpp` + `src/index_memory_benchmark.cpp`:
  `benchmark_index_memory(language, books, stopwords, output_dir)`, SPEC section 9's `index_memory`
  experiment. C++ has no equivalent to the Java module's `Runtime.totalMemory()/freeMemory()`-after-GC
  estimate, since there is no garbage collector or heap-size introspection API; the closest honest
  substitute is the operating system's own **peak resident set size** (`getrusage`'s `ru_maxrss`,
  POSIX — available on both macOS and Linux, this project's only targets), measured before and after
  each step. Two measurements, both single-shot (no `measure_elapsed_ms` repetitions: RSS is a
  point-in-time OS counter, not something warmup/averaging applies to):
  - `in_memory_index`: building the shared `InvertedIndex` from `books` — what every structure's
    in-memory representation starts from.
  - `monolithic`: on top of that, re-parsing the just-written monolithic JSON file back into memory
    (the `load_monolithic` step `benchmark_index_query`, Entry 28, already does for real queries).
  `hierarchical` and `mongo` are deliberately **not** measured: neither has an equivalent "loaded into
  this process" state in this project's design — hierarchical reads small per-term files on demand with
  nothing kept resident across queries, and mongo's data lives in the database server's own process,
  not this one (the same limitation the Java module's own comment notes for its client-only view).
- `ru_maxrss`'s platform-dependent unit handled explicitly: bytes on macOS, kilobytes on Linux
  (`#if defined(__APPLE__)`).
- Deltas clamped to zero (`std::max<long>(0, after - before)`), documented as meaning "this step did
  not push the peak any higher than an earlier, larger step already had" rather than a meaningless
  negative number — `ru_maxrss` is a **monotonic peak for the whole process**, not a per-object
  counter, so later, smaller steps can legitimately measure as zero if an earlier step already set a
  higher peak. This single-process ordering caveat is this benchmark's own honest limitation,
  documented in the header rather than hidden, the same way Java's own file documents its GC
  estimate's imprecision.
- `main.cpp`'s `benchmark` command gained `index_memory`.
- 1 new test (checks row shape, not specific magnitudes — RSS numbers are inherently
  platform/allocator-dependent and would make a unit test flaky if asserted precisely). Suite total:
  166 tests.
- Ran it for real against the 15 downloaded books: building `in_memory_index` grew the peak by
  **~12.86 MB**; re-parsing the monolithic JSON added **~5.24 MB** more — both plausible, non-zero,
  real numbers for a modest real-text corpus, not noise.

### Why
- **Peak RSS instead of trying to emulate Java's GC-based estimate.** Forcing an artificial "GC-like"
  moment in C++ (there is nothing to force — allocations are freed deterministically by destructors,
  not by a collector) would be inventing a C++ concept that does not exist just to mirror Java's
  method; using the OS's own, real memory accounting is the more honest choice for this language,
  even though it is a different kind of estimate with its own caveats (monotonic peak, process-wide)
  rather than Java's (heap after a *requested*, not guaranteed, GC).
- **`hierarchical`/`mongo` left unmeasured rather than reported as a misleading `0`.** Writing a `0`
  bytes row for them would read as "this structure uses no memory," which is not what is actually true
  (it uses *no resident, cached, in-process* memory, because its whole design never loads anything
  persistent into this process) — a meaningfully different, more informative statement than a bare
  zero, and worth the reader's attention rather than silently averaged into a comparison table.
- **Two measurements sharing one process, with the ordering caveat documented, instead of forking a
  fresh process per measurement for cleaner isolation.** A `fork()`-per-structure design would give more
  accurate, order-independent numbers, but introduces real complexity (coordinating with GoogleTest's
  own process model, file descriptor handling across the fork) for a benchmark whose own purpose is
  already an *estimate* by nature, in every language's version of it; the honest, documented caveat is a
  smaller, more proportionate cost than the added design risk.

---

## Entry 42 – index_disk, the last of the 12 SPEC section 9 experiments (2026-10-01)

### What was done
- `include/stage1/index_disk_benchmark.hpp` + `src/index_disk_benchmark.cpp`:
  `benchmark_index_disk(language, books, stopwords, output_dir)`, SPEC section 9's `index_disk`
  experiment (the course PDF's own "Memory and disk usage"). Builds the index once, writes it through
  `monolithic` and `hierarchical`, and reports per structure: `bytes` (total file size on disk,
  recursive), `files` (file count), and `terms`/`postings` — the index's *logical* size, identical
  across structures by construction (same vocabulary, same postings; the two are read from the same
  `InvertedIndex` object, so there is nothing to independently verify here, unlike experiments comparing
  two separately-built structures). `mongo` deliberately left out: its real disk footprint needs
  MongoDB's own `collStats` command, whose numeric BSON fields vary in type across driver/server
  versions and need careful handling this machine (no Docker) cannot verify — same "don't ship what
  can't be checked now" discipline already used for `allocated_bytes` (Entry 35) and the Mongo gaps in
  Entries 28/41.
- `main.cpp`'s `benchmark` command gained `index_disk`. **This is the 12th and last of SPEC section 9's
  experiments** — every one of `datalake_write`, `datalake_lookup`, `datalake_incremental`,
  `datalake_recovery`, `datalake_storage`, `metadata_insert`, `metadata_query`, `index_build`,
  `index_query`, `index_update`, `index_memory`, `index_disk` now has a working, tested, real-run
  implementation in this module.
- 1 new test (checks `monolithic`/`hierarchical` agree on `terms`/`postings`, and that `hierarchical`'s
  file count equals the term count while `monolithic`'s stays at 1). Suite total: 167 tests.
- Ran it for real against the 15 downloaded books.

### A result that mirrors index_update's finding, in the opposite direction
`monolithic`: 738,543 bytes in 1 file. `hierarchical`: 356,666 bytes across 30,396 files (one per
term, matching `terms` exactly) — **less than half the disk space**, despite needing tens of thousands
of files. The reason is the inverse of Entry 39/40's finding: JSON repeats each term as a quoted string
key plus structural punctuation (`"term":[...]," `) for every single entry, while hierarchical's files
contain *only* the postings, one bare integer per line — the term itself is never written inside a
file, it is the filename. So the very same "one small file per term" design that made `hierarchical`
dramatically more expensive to *update* (Entry 39) is what makes it meaningfully cheaper to *store*.
Both entries 39/40 and this one measure real, opposite-direction consequences of the same structural
choice, which is precisely the kind of trade-off SPEC section 6 asks the report to discuss — now with
numbers on both sides of it, from the same 15-book dataset.

### Why
- **`terms`/`postings` reported once per structure even though they are always identical.** Having them
  sit in the same CSV, next to each structure's very different `bytes`/`files`, is what makes "same
  logical data, different physical cost" directly visible without needing to cross-reference a separate
  table — the exact point Java's own version of this experiment makes with the same two metrics.
- **No repetitions, no timing.** `index_disk`, like `datalake_storage` (Entry 35) and `index_memory`
  (Entry 41), measures a static property of a finished structure, not an operation's duration;
  `measure_elapsed_ms`'s warmup/averaging methodology has nothing to apply to here.
- **Mongo skipped rather than half-implemented.** A `collStats`-based number that might silently read as
  `0` or throw on a BSON type mismatch this environment cannot exercise would be worse than an honest
  gap — the same reasoning already applied twice this session (`allocated_bytes`, `index_memory`'s
  Mongo row) kept consistent a third time, rather than making an exception just to say every experiment
  covers all three structures.

## Entry 43 – Splitting main.cpp: argv parsing vs. command wiring (2026-10-01)

### What was done
- `src/main.cpp` (201 lines) split in two, with no behavior change:
  - `src/main.cpp` (45 lines): only parses `argv`, validates `<N>`, prints usage, catches any escaping
    exception as `[fatal]`, and dispatches to one of the two commands below.
  - `include/stage1/cli_commands.hpp` + `src/cli_commands.cpp`: `run_pipeline_command(steps)` and
    `run_benchmark_command(experiment)`, the former `run_pipeline`/`run_benchmark` moved verbatim
    (wiring of the concrete components, the in-memory index rebuild on startup, the 12-way benchmark
    dispatch, CSV output). Only two mechanical changes: they now live in `namespace stage1` instead of
    an anonymous namespace (they must have external linkage to be callable from another translation
    unit, `main.cpp`), which also drops every `stage1::` prefix; `describe()` stays file-local in
    `cli_commands.cpp`, `print_usage()` stays file-local in `main.cpp`.
- `CMakeLists.txt`: `src/cli_commands.cpp` added to the `search_engine_stage1` executable target, not to
  `stage1_core`.

### Verification
Rebuilt with no warnings; all 167 tests pass (the usual 3 Mongo tests skipped, no Docker here). The
binary still prints usage and exits 1 with no arguments and with `pipeline 0`, exactly as before.

### Why
- **Two jobs, two files.** `main.cpp` had grown from Entry 25's thin wiring into CLI parsing plus two
  full commands plus a 12-branch benchmark dispatch, one branch added per experiment (Entries 29-42).
  Separating "what did the user type?" from "what does each command assemble and run?" means adding a
  command (e.g. a future `search`/`status`, like Java's) touches the dispatch in one place and the
  wiring in the other, instead of growing one long file in both directions.
- **Same shape as the Java module.** Java separates a thin `Main.java` (reads the command, prints the
  result) from `SearchEngine.java` (the only place the pieces get connected). Keeping the two
  implementations' entry points recognizably parallel helps whoever compares them, the grader included
  (the same reason Entry 25 gave for mirroring Java's `pipeline <N>` CLI shape).
- **`cli_commands.cpp` belongs to the executable, not `stage1_core`.** It is the only code that reads
  `STAGE1_SHARED_DIR`/`STAGE1_DATA_DIR`/`STAGE1_BENCHMARKS_DIR`, and those are `PRIVATE` compile
  definitions of the executable target (Entry 25): where *this binary's* `shared/`, `data/` and
  `benchmarks/` directories are is a decision of the program, not of the reusable library the tests
  also link. Put in `stage1_core`, the file would not even compile (the macros are not defined for that
  target); left out of every target (the state right after the split), `main.cpp` would compile fine
  against the header's declarations but the link would fail on the missing definitions.
- **A pure move, not a redesign.** Keeping the function bodies byte-for-byte equivalent makes the diff
  trivially checkable against the previous `main.cpp`, and the unchanged 167-test suite plus identical
  CLI behavior is enough evidence that nothing changed.

### Honest difference from Java (not fixed here)
Java's `SearchEngine` is built from an `AppConfig` and is also what its end-to-end test assembles (with
a fake `BookSource`), so the program and the test exercise the very same wiring. `cli_commands.cpp` is
still untested: it lives in the executable and reads compile-time directories, so no test links it. The
C++ equivalent of Java's tested assembly remains `run_pipeline_step` in `stage1_core`, covered by
`tests/pipeline_test.cpp` (Entry 25); the untested part is only the concrete-type wiring and the
benchmark dispatch. Making it testable would mean passing the three directories in as parameters so the
file could move into `stage1_core`, a possible later improvement, deliberately left out of a
behavior-preserving split.

## Entry 44 – `search` and `status` commands: querying the persisted index from the binary (2026-10-01)

### What was done
- `include/stage1/index_readers.hpp` + `src/index_readers.cpp` (in `stage1_core`):
  `monolithic_postings_fetcher(path)` and `hierarchical_postings_fetcher(root)`, the readers that used
  to be private helpers inside `index_query_benchmark.cpp` (Entry 28), moved out unchanged so `search`
  can reuse them. Each returns the postings-fetcher function `query_and` already accepts, the same shape
  as the existing `mongo_postings_fetcher`. One behavior change: the monolithic reader now throws
  `cannot open index file for reading: <path>` when the file is missing, instead of letting
  `nlohmann::json::parse` fail with an unhelpful "unexpected end of input". `index_query_benchmark.cpp`
  now calls the shared readers.
- `ControlLog::ids()`: every recorded id, ascending, each once (a sorted copy of the internal
  `unordered_set`, whose iteration order is unspecified). Until now the class could only answer
  `contains(id)`, which is enough for the pipeline but not for counting or listing.
- `search_engine_stage1 status` (`run_status_command`): dataset size (`shared/book_ids.txt`), how many
  ids the control logs record as downloaded and as indexed, and which are downloaded but not indexed yet
  (`std::set_difference` over the two sorted `ids()` lists). It counts what the logs hold, like Java's
  `control.downloaded().size()`, not only ids still in the current dataset.
- `search_engine_stage1 search <words...>` (`run_search_command`): tokenizes the query with the same
  tokenizer and stopwords as indexing, runs `query_and` against the monolithic index `pipeline` wrote
  (read with `monolithic_postings_fetcher`, never by re-reading the books), and prints each match's id
  plus its title from `MetadataStore`. `main.cpp` joins every word after `search`, so
  `search whale island` and `search "whale island"` are the same query.
- `kIndexPath` in `cli_commands.cpp`: one constant for `data/datamarts/inverted_index.json`, used by
  both `pipeline`'s `MonolithicIndexWriter` and `search`'s reader, so they cannot drift apart.
- Tests: 4 for the readers (each one reads back what its writer wrote, unknown term gives empty
  postings, missing monolithic file throws, and AND queries through both readers equal the in-memory
  index's answers) and 1 for `ids()` (plus an `ids().empty()` check on a fresh log). Suite total: 172.

### Verification (real data, 15 books)
- `status` with nothing downloaded: 0 / 0 / 0. After `pipeline 5` (an odd step count, chosen on
  purpose): downloaded 3, indexed 2, pending 1 (book 11). The next `pipeline` run's first action was
  `indexed book 11`, so it resumed the pending work before downloading anything new. It then finished
  all 15 books in ~18 s. Final `status`: 15 / 15 / 0.
- `search` on every query in `shared/queries.txt`. Matches: adventure 9, island 9, love 13, ship sea 9,
  king queen 8, monster creature 6, whale 4, detective crime 4, war peace 9, mother father 11.
- **Independent cross-check: 10/10 identical.** For each query, a shell script listed the books whose
  `body.txt` contains every term as a whole `[A-Za-z0-9]+` run, case-insensitively (SPEC section 5's
  token definition), using plain `grep` instead of any project code. Its result matched `search`'s
  exactly for all 10 queries. One pitfall worth recording for anyone repeating this: in this shell
  `grep` resolved to `ugrep -I`, which silently skips files it considers binary, and under `LC_ALL=C`
  some books' UTF-8 curly quotes made them look binary (0 hits for `love`). `/usr/bin/grep -a` was used
  instead. A `grep -w` check would also have been subtly wrong: it treats `_` as part of a word, while
  the tokenizer splits on it (Gutenberg marks italics as `_word_`).
- Tokenization of the query itself: `search "The WHALE, and the Island!"` searches `whale island`
  (case, punctuation and stopwords handled exactly like the books). A query of only stopwords prints a
  "no searchable terms" message and exits 0. A missing index prints "run `pipeline <N>` first" and
  exits 1. `search` with no words prints usage.

### Why
- **Querying is graded, and the binary could not query.** The assignment's evaluation criteria give 30%
  to "proper functioning of downloading, indexing, and querying modules". `query_and` existed and was
  tested (Entry 22), but only the tests and the `index_query` benchmark could reach it. Someone running
  this module's binary could download and index, but not search. Java already had `search`/`status`.
- **Read the persisted index, don't rebuild it.** `pipeline` rebuilds its in-memory index from the
  books' bodies at startup (Entry 25's known cost). Doing the same for every single `search` would
  re-tokenize the whole collection per query. Reading the monolithic file instead also shows that what
  the pipeline writes to the datamart is actually usable for search, which is the point of a datamart.
  The readers already existed (Entry 28), so the cost was moving them, not writing new parsing code.
- **Readers in `stage1_core`, commands in the executable.** The readers take their path as a parameter,
  so they are reusable and testable with a `TempDir`, unlike `cli_commands.cpp`, which reads
  compile-time directory macros (Entry 43).
- **`ids()` returns a sorted copy, not a reference to the internal set.** Callers cannot modify the
  log behind `mark()`'s back, the internal container stays an implementation detail, and the sort makes
  the output deterministic and directly usable by `std::set_difference`, which requires sorted input.
- **Exit codes distinguish "nothing to search" (0) from "cannot search" (1)**, so a script chaining
  commands can tell a valid empty result from a missing index.
- **`status` stays read-only in intent.** Its only side effect is `ControlLog` creating an empty
  `data/control/` the first time, documented in the header rather than worked around.

### Known gaps, deliberately left
- `search` only reads the **monolithic** index, because that is what `pipeline` writes (Entry 25's
  default). Switching `pipeline` to another `IndexWriter` means switching `search`'s reader too, which is
  why both sit next to `kIndexPath`'s comment.
- Cosmetic: the header line shows the tokenized terms as typed, so `search whale whale island` prints
  `whale whale island` (the result itself is right: `query_and` de-duplicates).
- `cli_commands.cpp` is still untested as a whole (Entry 43). The new logic it calls (`ids()`, the
  readers, `query_and`) is unit-tested, and the commands were verified by the real run above.
- The root `README.md`'s C/C++ section is still the group's original template (`cd c/`, "GCC and
  Make", the binary run with no arguments). It is a shared group file, so fixing it is left to a group
  decision rather than changed unilaterally from this module.

## Entry 45 – Module folders mirroring the Java package layout (2026-10-02)

### What was done
`include/stage1/`, `src/` and `tests/` were each split into the same module subfolders as the Java
module's packages (`java/stage1/src/main/java/es/ulpgc/bigdata/...`):

| C++ folder | Java package | Contents |
|---|---|---|
| *(root)* | `Main`, `SearchEngine`, `EndToEndTest` | `main.cpp`, `cli_commands`, `smoke_test` |
| `crawler/` | `crawler` | `book_source`, `book_splitter`, `gutenberg_client`, `http_client`, `curl_http_client`, `download_result` |
| `datalake/` | `datalake` | `datalake` (interface), `book_based_`, `range_based_`, `time_based_datalake` |
| `datamart/index/` | `datamart.index` | `inverted_index`, `tokenizer`, `stopwords`, `index_writer`, the three writers, `index_readers` |
| `datamart/metadata/` | `datamart.metadata` | `metadata` (≈ `MetadataParser`), `metadata_store` (≈ `SqliteMetadataRepository`) |
| `control/` | `control` | `control_log` (≈ `ControlFiles`), `book_id_list`, `pipeline` (≈ `PipelineController`) |
| `query/` | `query` | `query_engine` (≈ `SearchService`) |
| `benchmark/` | `benchmark` | `benchmark` (timer + CSV), `sample_books` (≈ `BenchmarkBooks`), `query_list`, the 12 experiments |
| `util/` | *(none)* | `file_io`, `text_utils` |

`tests/fakes/` and `tests/support/` are unchanged.

Strictly mechanical, in two steps: `crawler/` first as a worked example, then every other module.
- 110 files moved with `git mv`, so each file keeps its history (`git log --follow`).
- 226 `#include "stage1/<name>.hpp"` lines rewritten to `"stage1/<module>/<name>.hpp"`.
- Source paths updated in both `CMakeLists.txt`.
- One new line in `tests/CMakeLists.txt`: `target_include_directories(stage1_tests PRIVATE
  ${CMAKE_CURRENT_SOURCE_DIR})`. Without it, a test moved into a subfolder can no longer find
  `"fakes/..."`/`"support/..."`: a quoted include is first searched next to the including file, which
  is now `tests/crawler/` rather than `tests/` (confirmed by building without it first:
  `'fakes/fake_http_client.hpp' file not found`).
- No logic, name, comment or namespace changed. The diff contains nothing but `#include` lines and
  CMake paths.

### Verification
- A dry run of the move list (checking that only `cli_commands.hpp`, `main.cpp`, `cli_commands.cpp`
  and `smoke_test.cpp` would stay at the roots) before moving anything.
- After moving: no flat `"stage1/<name>.hpp"` include left except the root-level `cli_commands.hpp`.
- An existence check of every CMake source path: 72 listed, 72 `.cpp` files on disk, 0 missing. This
  check caught a slip in the move script, which had missed the last entry of each source list because
  it carries the closing `)` on the same line. It was fixed by hand.
- Clean rebuild, all 172 tests pass, and `status`/`search whale island` give the same output as before.

### What the folders now make visible
Counting cross-module `#include`s gives a layered graph with no cycles:
```
util               -> (nothing)
crawler, datalake,
datamart/index,
datamart/metadata  -> util
query              -> datamart/index
control            -> crawler, datalake, datamart/index, datamart/metadata, util
benchmark          -> control, datalake, datamart/index, datamart/metadata, query, util
(root: CLI)        -> everything
```
This is the assignment's architecture, now readable from the folder tree alone. The datalake and the
two datamarts are independent of each other. The control layer is the only module that orchestrates
them (crawler → datalake → metadata → index). Search needs nothing but the index. The same graph is a
ready-made diagram for the report's "System architecture" section.

### Why
- **Same shape as the Java module.** Someone comparing the three implementations (the grader
  included) finds the same responsibilities under the same names in each one. The flat folder of
  ~40 headers gave no hint of the datalake/datamart/control architecture at all.
- **"Code quality (20%): structure, modularity"** is an explicit grading criterion, and module folders
  are the most direct evidence of modularity.
- **C++ conventions kept where they differ from Java's.** The split is `include/` + `src/` + `tests/`
  mirroring each other, not Maven's `src/main/java` + `src/test/java`, which is a Maven convention
  rather than part of the module design.

### Alternatives considered and rejected
- **Wrapping everything in `cpp/stage1/`** (like `java/stage1/`, `python/stage_1/`). It would require
  editing the group's root `.gitignore` (`cpp/build/`, `cpp/data/`, `cpp/benchmarks/work/`). Worse,
  `.gitignore` ignores every `*.csv` and re-includes only `!cpp/benchmarks/results/*.csv`, so the 12
  committed result CSVs would silently become ignored at the new path. It would also change CMake's
  `../shared` paths and need a full rebuild and new local editor settings.
- **`model/` and `config/` folders.** Java's `model` classes have C++ equivalents that live next to
  their logic (`BookMetadata` in `metadata.hpp`, `StoredBook` in `metadata_store.hpp`), and splitting
  them out would change code, which this restructure deliberately did not. There is no `config/`
  equivalent: this module's configuration is CMake compile definitions, not a config class.
- **Namespaces per module** (`stage1::crawler`, ...), the closest C++ analogue of Java packages. It
  would touch nearly every file's code, not just its location. It is a possible later step, kept
  separate so that this change stays purely mechanical.
- **Smaller placement calls.**
  - `download_result` went to `crawler/`, not to a one-file `model/`, because only the crawler uses it.
  - `stopwords` went next to `tokenizer`, as Java's `Tokenizer.fromStopwordsFile` does.
  - `query_list` went to `benchmark/`, since it loads the benchmark query workload and nothing else
    uses it.
  - `util/` is new because `file_io`/`text_utils` are used by almost every module, whereas Java keeps
    such helpers inside each class.

## Entry 46 – A user guide for the module: `cpp/README.md` (2026-10-02)

### What was done
Added `cpp/README.md`, a short user guide covering:
- requirements, and where each one comes from on macOS;
- building and testing (`make`, `make test`);
- the four CLI commands (`pipeline`, `search`, `status`, `benchmark`): what each does, whether it
  needs the network, and what it writes;
- a quick start with real output;
- the layout of `data/`, mapped to the datalake, datamarts and control layer, plus three commands to
  check the CLI's answers against the files themselves;
- a by-hand test of resuming after an interruption;
- a table of edge cases with their exit codes;
- practical notes: benchmarks overwrite committed CSVs, zsh quoting, `make run ARGS=...`, starting
  over.

Every non-destructive command in it was run before committing and produces exactly the output the
guide shows. The resume scenario was already verified for real in Entry 44.

### Why
- **The "how" had no home.** How to use the module was scattered across Entries 25, 29, 43 and 44,
  and the DEVLOG is the wrong place to collect it. The DEVLOG records *why* decisions were taken and is
  append-only, whereas a usage guide must be edited in place whenever a command changes.
- **A module README, not the group README.** The root `README.md` belongs to the whole group (and its
  C/C++ section is still the original template, see Entry 44). `cpp/README.md` lives entirely in this
  module, so it can be kept accurate without touching shared files. The group can later link to it,
  as the root README already intends to do for `java/README.md`.
- **The assignment asks for it.** The repository must "include a README.md with detailed setup and
  execution instructions", and instructors must be able to try the pipeline quickly.
- **Commands safe to paste into zsh.** No inline `#` comments (interactive zsh may pass them on as
  arguments), and single quotes for literal queries (`!"` inside double quotes leaves zsh at a
  `dquote>` prompt). Both pitfalls actually happened while testing the CLI by hand.

## Entry 47 – The group's benchmark dataset agreement, written into the SPEC (2026-10-03)

### What was done
The group agreed on how every language runs the benchmarks: 200 real books, plus synthetic metadata.
Until now this was only implemented in Java and described in Java's report. It is now the shared
contract:
- `shared/SPEC.md`: new section 10, appended without changing any existing line.
  - **10.1, real books.** Datalake experiments run at N=200 and index experiments at N=50, 100 and
    200, on the 200 ids of `shared/book_ids.txt`, read from a `book` datalake the pipeline already
    filled. **A size N means the N books with the lowest ids** (ascending id order, as in Java's
    `BenchmarkBooks.fromDatalake`), not the first N lines of `book_ids.txt`. The section also gives
    reference counts every implementation must reproduce exactly: 58,834 terms / 360,970 postings at
    N=50, 78,820 / 759,087 at N=100, and 129,356 / 1,581,064 at N=200, taken from Java's
    `index_disk`.
  - **10.2, synthetic metadata.** Java's deterministic generator (`"Title " + i/2`,
    `"Author " + i/10`, ...) at N=1,000, 10,000 and 100,000, as `sqlite` and `sqlite_no_index`.
  - **10.3, other synthetic data.** Synthetic books are not compared across languages.
  - **10.4, results folders.** `benchmarks/results/real/` and `benchmarks/results/synthetic/`.
- Root `.gitignore`: the CSV exceptions became `!cpp/benchmarks/results/**/*.csv` and
  `!java/stage1/benchmarks/results/**/*.csv`. Every `*.csv` is ignored globally, and the old
  `results/*.csv` exceptions did not reach the new `real/` and `synthetic/` subfolders. Checked with
  `git check-ignore` before and after: those subfolders are now trackable, while `benchmarks/work/`,
  `data/` and any other CSV stay ignored.

### Why
- **The agreement has to live in the contract, not in one module's report.** Python and C++ must
  follow it the same way, and before this nobody could check their implementation against a written
  rule.
- **Prefixes by ascending id, as Java already does.** With N=50 or N=100, "lowest ids" and "first
  lines of `book_ids.txt`" select different books, so the two rules cannot be mixed. Following
  Java's rule keeps its existing `results/real/` comparable instead of forcing a re-run, and it
  makes every N a prefix of the next.
- **Reference counts as a contract test.** The tokenizer is identical by contract (section 5), so
  the same books must give the same term and posting counts in every language. Comparing two
  integers catches a wrong book set or a tokenizer drift before any time is compared.
- **Deterministic metadata generator; synthetic books excluded from comparisons.** Generating the
  same rows with integer division needs no random numbers. Random generators differ across languages
  even with the same seed (C++ `mt19937`, Python and Java all gave different sequences for seed 42),
  so cross-language synthetic *books* would silently be different datasets.

### Consequences for this module (not done yet)
- `load_sample_books` returns books in `book_ids.txt` order. The index benchmarks must sort by id
  and take prefixes of 50, 100 and 200.
- Download the 200 books (`pipeline 400`) and check the reference counts above.
- `metadata_insert`/`metadata_query`: switch from real books to the 10.2 generator, and add
  `sqlite_no_index`.
- Write the results into `results/real/` and `results/synthetic/`.
- The current `results/*.csv` (15 books) predate the agreement.

## Entry 48 – `sample_dataset/`: the group's 15 original books, raw and split (2026-10-03)

### What was done
Created the repository-level `sample_dataset/` that the assignment requires ("Provide a sample
dataset so instructors can quickly test the pipeline"). The group's root README already promised it,
and SPEC section 9 referenced it, but it did not exist.
- `raw/pg<ID>.txt`: the 15 original books (the first 15 lines of `shared/book_ids.txt`),
  downloaded unmodified from the SPEC section 2 URL. 7.7 MB, CRLF line endings.
- `book/<ID>/header.txt` and `body.txt`: the same books after the SPEC section 2 split, in the
  SPEC section 3 `book` layout. 6.3 MB.
- `book_ids.txt` (the 15 ids), `SHA256SUMS` (45 files), and `.gitattributes`
  (`raw/** -text`, `book/** -text`).
- `README.md`: contents, the three ways to use it, reference values, how to check a copy and how
  to rebuild `raw/`, and the license.

### Verification
- An independent Python script split every raw file following SPEC section 2. All 15
  headers and bodies were byte-identical to what this module's pipeline had stored when it
  downloaded the same books (Entry 44 and the 200-book run). That stored output is what `book/`
  contains.
- The sample's counts are 30,396 distinct terms and 89,727 postings, the same as the C++ pipeline
  and its `index_disk` benchmark give for these 15 books.
- `shasum -a 256 -c SHA256SUMS`: 45/45 OK. `git check-attr`: the `text` attribute is unset for
  `raw/` and `book/`.

### Why
- **The 15 original books.** They are small enough to live in git (14 MB with both forms, against
  129 MB for the 200), and their counts were already known and checked. The pipeline processes them
  in about 18 seconds.
- **Raw files, unmodified.** An offline run that reads them instead of the URL exercises the whole
  pipeline, including the header/body split. That split is the step most likely to break on a new
  book, and a pre-split sample would never test it. Keeping the files intact also keeps the Project
  Gutenberg License they carry.
- **Also the split books, in `book` layout.**
  - Java's benchmarks already accept "sample_dataset/ in book structure" as input, so `book/` is
    usable today without new code.
  - It is the *expected output* of SPEC section 2: any implementation can check its own split
    byte for byte, a reference as strong as the term/posting counts.
  - It matches the root README's description ("libros procesados").
- **Checksums and `-text`.** Gutenberg revises files over time, and git may convert line endings on
  some platforms (Windows with `core.autocrlf`). Either would silently change the dataset. The
  checksums detect it, and `-text` prevents the conversion.

### Not done yet
- No implementation has an offline mode reading `raw/` yet. In this module it would be a new
  `BookSource` implementation next to `GutenbergSource`, the same seam the tests already use for
  their fake source.
- SPEC section 10.1 still says `sample_dataset/` "does not exist yet". That sentence belongs to the
  shared contract, so it is left for an explicit decision.

## Entry 49 – Offline pipeline mode: `pipeline <N> --offline` reads `sample_dataset/raw/` (2026-10-03)

### What was done
- `include/stage1/crawler/local_file_source.hpp` + `src/crawler/local_file_source.cpp`:
  `LocalFileSource`, a second `BookSource` next to `GutenbergSource`. `fetch(id)` reads
  `<dir>/pg<ID>.txt` in binary mode and returns its bytes untouched: the `\r\n` normalization stays
  in `split_book` (SPEC section 2), exactly as for a download. A missing file is a failed fetch, not
  an exception, so the pipeline leaves the book unmarked as it does after a failed download.
- `pipeline <N> --offline`:
  - `main.cpp` accepts an optional `--offline`; any other fourth argument prints usage and exits 1.
  - `run_pipeline_command(steps, offline)` takes the ids from `sample_dataset/book_ids.txt` instead
    of `shared/book_ids.txt`, and uses `LocalFileSource(sample_dataset/raw)` instead of
    `GutenbergSource`.
  - New compile definition `STAGE1_SAMPLE_DIR`, the same pattern as the other directory macros.
  - Everything after the source is selected is untouched. Both sources are built, and a
    `BookSource&` refers to the chosen one, so nothing downstream knows which mode it runs in.
- 3 tests:
  - the bytes come back unmodified (CRLF and UTF-8 included);
  - a missing file is a failure that names the file;
  - for all 15 real sample books, `LocalFileSource` + `split_book` reproduce `sample_dataset/book/`
    byte for byte. That test ties the sample and the splitter together on every test run.
  
  Suite total: 175.
- `cpp/README.md`:
  - the new mode is documented;
  - the quick start and the resume test now run offline (seconds, no network);
  - the full online dataset is described as `pipeline 400`, about 10 minutes;
  - figures left stale by the move to 200 books are fixed (the quick start still showed
    `dataset: 15`, and `pipeline 40` no longer reaches the end).
- `sample_dataset/README.md`: it no longer says that no implementation has an offline mode.

### Verification (real run, network blocked)
The real data was moved aside, and every HTTP(S) request was sent to a closed local port
(`https_proxy=http://127.0.0.1:9`).
- **Control:** the normal mode fetched nothing (`downloaded: 0`), so the network really was blocked.
- **Offline:** 15 books fetched and indexed in under a second (about 18 s when downloading).
  - `data/datalake/book/` was byte-identical to `sample_dataset/book/` (`diff -r` empty).
  - The index gave 30,396 terms and 89,727 postings, and `search whale island` returned the usual 3
    books.
- Every command the updated README shows was run, and its output matches.
- The real 200-book `data/` was restored afterwards.

### Why
- **A sample nobody can feed to the program is not a sample.** The assignment wants instructors to
  "quickly test the pipeline". Without a way to read `sample_dataset/`, the program could only
  download from the internet. The offline mode is what makes the sample usable, it gives the same
  result on every machine, and it removes the dependency on Gutenberg being reachable.
- **A new `BookSource`, not a new pipeline.** `BookSource` was introduced so the transport could be
  swapped, and the tests already relied on that. Adding one class and choosing it in the CLI keeps
  the split, datalake, metadata, index and control code identical in both modes. That is why the
  offline output can serve as evidence for the online one.
- **Sample ids, not the 200.** Offline, only the 15 sample books exist. Using `shared/book_ids.txt`
  would leave 185 ids failing on every step.
- **Same `data/` folder in both modes.** The sample files are byte-identical to what Gutenberg served
  when the sample was made. An offline run followed by an online one therefore gives the same
  `data/` as an online run alone: the online run just skips the 15 books already done.

### Two existing bugs found along the way (not fixed yet)
- **Misleading `downloaded book X` message.** `run_pipeline_step` returns the *decision* it took, not
  its outcome, and the CLI prints the decision. With the network blocked, `pipeline 3` printed
  `downloaded book 1342` three times while `status` reported 0 downloaded. No data is affected, since
  the book is correctly left unmarked and retried, but the message is false.
- **`HierarchicalIndexWriter::write()` never removes term files that are no longer in the index.** It
  only writes the current index's terms, which breaks its own contract (Entry 18: "make the structure
  match this index, not append"); the monolithic and Mongo writers do follow it. The pipeline's index
  only grows, so nothing is wrong there. But `benchmarks/work/` is reused between runs, and the
  benchmarks do not clear it first. Writing the N=50 index over the N=200 one would leave 129,356 stale
  files: `index_disk` would count files and bytes that are not in the index, and hierarchical
  `index_query` would return books outside the 50. It must be fixed before the id-ordered prefixes
  (step 3 of the benchmark adaptation).
- Seen while looking into it: the N=200 hierarchical index holds **7,251,967 bytes of data**, exactly
  Java's `bytes` value, but occupies **~530 MB of disk blocks** (Java: 530,001,920 bytes allocated).
  Each of its 129,356 small files takes at least one 4,096-byte block, about 73 times the data size.

## Entry 50 – `HierarchicalIndexWriter::write()` now replaces its folder, and `index_build` empties it untimed (2026-10-03)

### What was done
- **Test first.** `HierarchicalIndexWriter.WritingAgainReplacesThePreviousContents` (the same name as
  the existing Mongo test, since it checks the same contract) writes an index with `car` and `boat`,
  then one with only `car`. Before the fix it failed: `B/boat.txt` was still there (Entry 49's
  finding).
- **The fix.** `write()` calls `std::filesystem::remove_all(root_)` before writing, so the folder ends
  up holding exactly the given index. The header now states that the root folder belongs to the
  writer. `update_terms()` is unchanged: it only adds or rewrites the given terms, which is all an
  update with new books ever needs.
- **`index_build`.** `run_and_record` takes a `reset` that `measure_elapsed_ms` runs before each
  repetition, untimed. For the hierarchical structure it empties the folder; for the other two it does
  nothing. No other benchmark needed a change: `index_update` already writes its base index in its
  untimed setup, and `index_disk`, `index_memory` and `index_query` time no `write()`.
- Suite total: 176.

### Measured on the 200 real books
- Deleting the 129,356 files of the N=200 hierarchical index takes **22.5 s** on this machine (macOS,
  APFS).
- `index_build` at N=200, mean of 5 runs: monolithic **2,567 ms**, hierarchical **23,566 ms**, with
  the deletion outside the measurement. Had it been timed, every hierarchical repetition would have
  measured about twice the building cost.
- Java's report gives 1.69 s and 11.34 s for the same experiment, on Linux with an NVMe SSD. The ratio
  between structures is similar (hierarchical about 7-9 times monolithic), but the absolute values are
  not comparable across machines. Creating and deleting small files is a filesystem cost, which is one
  more argument for running every language on the same machine (still an open point with the group).

### Why
- **Correctness of the contract, not just of the benchmarks.** `write()` means "make the structure
  match this index" (Entry 18). Monolithic follows it by rewriting its file, and Mongo by
  `delete_many` before inserting. Hierarchical silently appended instead. In the pipeline this was
  harmless, because the index only grows. In the benchmarks, the N=50 index written over the N=200
  one would have kept 129,356 files: `index_disk` would have counted files that are not in the index,
  and hierarchical `index_query` would have returned books outside the 50.
- **Fix it in the writer, and keep the cleanup out of the timing.** Clearing only in the benchmarks
  would have hidden the bug instead of fixing it. Clearing only in the writer would have pushed 22 s of
  deletion into every timed repetition. Doing both keeps the contract correct and the measurement
  honest. "Setup outside the measurement" is the rule the Java module follows too.
- **A known risk.** The local `backup/cpp-completo` branch (an earlier C++ attempt, never merged)
  records `std::filesystem::remove_all` failing with "Directory not empty" on a ~460k-file
  hierarchical index during a 500-book run. Nothing like it happened here at 129k files. If it ever
  does, `write()` throws with that message, and that branch's mitigation (retrying the removal) is
  the fix to bring in.

## Entry 51 – The pipeline reports what a step actually did, and stops at the first failure (2026-10-03)

### What was done
- `include/stage1/control/pipeline.hpp`: `run_pipeline_step` now returns a `StepResult`, made of the
  `ControlDecision` it acted on, `completed`, and `failure` (why it did not complete). Java's
  `StepResult` plays the same role. It used to return the bare decision.
- `src/control/pipeline.cpp`: `perform_download` and `perform_indexing` return the reason they could
  not finish, or an empty string when they did. The possible reasons are the fetch's own error (for
  example "Couldn't connect to server" or "HTTP 404"), "no START/END markers, book discarded (SPEC
  section 2)", and "no metadata row for it". The marking rules are unchanged: a book is marked only
  after its work fully succeeded.
- `src/cli_commands.cpp`: `describe` prints `could not download/index book X: <reason>` (to stderr) for
  an incomplete step, instead of `downloaded book X`. The loop **stops at the first failed step** with
  exit code 1, after printing `stopping; run pipeline again to retry`.
- `tests/control/pipeline_test.cpp`: the 5 existing tests now also check `completed` and `failure`,
  and a new test covers indexing a book that has no metadata row. Suite total: 177.
- `cpp/README.md`: one row in the edge-case table for an unreachable Project Gutenberg.

### Verification (real run)
The 200-book `data/` was moved aside and HTTP(S) was sent to a closed local port, the same scenario as
Entry 49.
- Before this change, `pipeline 3` printed `downloaded book 1342` three times while nothing was
  downloaded.
- It now prints `could not download book 1342: Couldn't connect to server`, then `stopping; run
  pipeline again to retry`, and exits 1. `status` still says 0 downloaded.
- `pipeline 30 --offline` is unaffected (15/15, exit 0). The real data was restored afterwards.

### Why
- **The message must describe the outcome, not the intention.** A pipeline log is what someone reads
  to know what happened. "downloaded book 1342" for a download that failed is worse than no message.
  The control layer itself was always right, since the book was never marked; only the report was
  wrong.
- **Stopping instead of looping.** A failed book stays unmarked, and `next_control_action` always
  picks the first unmarked candidate (indexing first). The very next step would therefore choose the
  same book and, with the network still down, fail the same way: `pipeline 400` would print 400
  identical failures. Stopping prints the failure once, and the non-zero exit code lets a script tell
  a run that made no progress from one that finished.

### Known limitation, not addressed here
A book that can **never** succeed, such as one served without START/END markers, is discarded without
being marked, exactly as SPEC section 2 requires. It is then chosen again on every run, so every run
now stops at it, and the books after it in `book_ids.txt` are never reached. The old loop had the same
problem, only hidden behind misleading messages. None of the 200 current books is affected, since all
were checked to have both markers. If one ever is, the fix belongs in the shared contract (for example
a "discarded" control file), not in one implementation.

## Entry 52 – Benchmark adaptation, step 2: `load_sample_books` returns books in ascending id order (2026-10-03)

### What was done
- `src/benchmark/sample_books.cpp`: after loading, the books are sorted by `book_id`. The header now
  states the order and points to SPEC section 10.1.
- `tests/benchmark/sample_books_test.cpp`: `PreservesCandidateOrder` asserted the old behaviour
  (84, 5, 1342 kept in candidate order). It was replaced on purpose by
  `ReturnsBooksInAscendingIdOrderWhateverTheCandidateOrder` (5, 84, 1342, each body still matching its
  id), which failed before the change. Suite total: 177.

### Verification
An independent script computed the term and posting counts of the real books two ways: the N lowest
ids, and the first N lines of `book_ids.txt`.

| N | Lowest ids | First N lines of `book_ids.txt` | Java (SPEC 10.1) |
|---|---|---|---|
| 50 | 58,834 / 360,970 | 57,780 / 362,160 | 58,834 / 360,970 |
| 100 | 78,820 / 759,087 | 98,858 / 803,819 | 78,820 / 759,087 |
| 200 | 129,356 / 1,581,064 | 129,356 / 1,581,064 | 129,356 / 1,581,064 |

"Lowest ids" reproduces Java exactly at every size. File order would have measured different books at
N=50 and N=100, which is the risk the agreement was written to prevent.

### Why
- **The order is part of the dataset definition.** With the same 200 books, "the first 50" is only
  well defined once the order is fixed, and the group fixed it as ascending id (SPEC 10.1). Sorting
  inside `load_sample_books` means every benchmark receives the same order, so the prefixes of the
  next step can simply take the first N books.
- **Sorting at load time, not in each benchmark.** All 12 benchmarks receive their books from this one
  function. Sorting here is one change instead of twelve, and no benchmark can forget it.

## Entry 53 – Benchmark adaptation, step 3a: index experiments at N=50, 100 and 200 (2026-10-03)

### What was done
- `src/cli_commands.cpp`:
  - `index_build`, `index_query`, `index_update` and `index_disk` now run once per size in
    `kIndexSizes = {50, 100, 200}` (SPEC section 10.1). Each run uses the first N books of
    `load_sample_books`, which are the N lowest ids since Entry 52.
  - All sizes go into the same CSV, distinguished by `dataset_size`.
  - A size larger than the number of downloaded books is skipped with a message. If none fits, the
    command fails and asks for `pipeline 400`.
  - The per-size dispatch lives in `run_index_experiment`.
- `index_query` no longer runs a whole `benchmark_index_build` just to create its files. That meant 7
  timed repetitions of writing (and, since Entry 50, deleting) up to 129,356 files, all for nothing.
  The new `prepare_index_query(books, stopwords, index_dir)` builds the index once and writes it,
  untimed, to the paths `benchmark_index_query` reads, plus Mongo if reachable. Both functions take
  those paths from one place (`monolithic_file`, `hierarchical_root`).
- `index_memory` keeps a single size for now, because its peak-RSS measure needs one process per size
  (step 3b).
- 1 new test, `PrepareWritesExactlyWhatTheQueriesRead`. Suite total: 178.

### Verification (real data, 200 books)
- `index_disk` gives exactly the SPEC 10.1 reference values at every size:
  - N=50: 58,834 terms and 360,970 postings;
  - N=100: 78,820 and 759,087;
  - N=200: 129,356 and 1,581,064.
- The hierarchical file count equals the term count at every size. N=50 was written over the 129,356
  files left by an earlier N=200 run, which is exactly the case Entry 50 fixed. Before that fix,
  N=50 would have counted 129,356 files.
- `index_query` at all three sizes took 78 s of wall time. Mean of 5 runs, loading the structure plus
  answering the 10 queries:

| N | monolithic | hierarchical |
|---|---|---|
| 50 | 71.1 ms | 1.3 ms |
| 100 | 86.6 ms | 0.4 ms |
| 200 | 205.3 ms | 0.9 ms |

### A comparability problem found, to settle with the group
These `index_query` numbers rank the structures the opposite way from Java's: monolithic 4.5 µs and
hierarchical 48.3 µs `per_query` at N=200. The two modules measure different things:
- **This module** times opening the structure *and* answering the 10 queries, cold, in every
  repetition (Entry 28's design). Monolithic must parse its whole 8.9 MB JSON each time, while
  hierarchical opens only the ~16 files the query terms need.
- **Java** opens the index outside the measurement and times only the queries.

Both are legitimate questions ("first query on a cold structure" against "steady-state query
cost"), but their numbers cannot be put side by side. SPEC section 9 fixes the CSV columns, not what
each experiment's metrics mean. Fixing the metric of each experiment is the open point already raised
with the group, and it has to be settled before the official run. Nothing was changed unilaterally
here.

## Entry 54 – Benchmark adaptation, step 3b: `index_memory` per size, measured with allocator in-use bytes instead of peak RSS (2026-10-03)

### What was done
- `index_memory` now runs at N=50, 100 and 200 like the other index experiments (`run_index_experiment`
  in `cli_commands.cpp`), all in one process.
- Its measure changed. It used to be getrusage's `ru_maxrss` (Entry 41), the peak resident memory of
  the whole process. It is now **the bytes the allocator reports as in use**, read right before and
  right after each step while what it built is still alive:
  - macOS: `malloc_zone_statistics(nullptr, ...)`, all zones;
  - Linux: glibc's `mallinfo2()`, `uordblks + hblkhd`.

  Metric `heap_delta`, unit bytes, structures `in_memory_index` and `monolithic` as before. This is
  the C++ counterpart of the heap usage the Java module reads from its JVM.
- Test updated: the metric is `heap_delta`, and both values must now be strictly positive, which the
  old peak measure could not guarantee. Suite total: 178.

### How the measure was chosen
1. **Peak RSS cannot compare sizes within one process.** A peak never goes down, so after N=50, the
   N=100 measurement would only show how far it exceeds N=50's peak.
2. **First attempt, abandoned: one child process per measurement** (`fork`). A small probe showed
   that on macOS a forked child's `ru_maxrss` starts at 0 MB, so isolation does work. The monolithic
   load then grew with N (14.8 / 26.1 / 38.6 MB). But the in-memory index gave 38.6 / 48.6 / 89.3 MB in
   one run and **43.0 / 29.2 / 22.8 MB** in the next, with the same code. RSS also moves with page
   reuse and with macOS memory compression of idle pages, such as the 200 loaded books. A peak of
   resident pages is not a stable measure of what a data structure holds.
3. **In-use bytes, verified before adopting them.** A probe program linked against `stage1_core` built
   the index at N = 50, 100, 200, 200, 100, 50, 200, all in one process. It gave 16.2 / 30.1 / 60.5 MB
   every time, whatever the order. The fork code (and a CSV reader written for it) was uncommitted,
   so it was discarded rather than kept as unused code.

### Results (200 real books; two runs identical to 0.1 MB)
| N | in-memory index (C++) | monolithic loaded (C++) | Java monolithic `heap_after_open` |
|---|---|---|---|
| 50 | 16.2 MB | 14.4 MB | 18.5 MB |
| 100 | 30.2 MB | 25.6 MB | 48.3 MB |
| 200 | 60.5 MB | 50.0 MB | 100.3 MB |

Both C++ structures grow almost linearly with N. The C++ figures are about half of Java's at N=200,
which is plausible: every Java object carries a header, and Java's posting lists box their integers.
The metrics are not identical, though. Java reports the whole heap after opening; this module
reports what the structure itself adds. That belongs to the open "metrics per experiment" discussion
with the group (Entry 53).

### Caveats
- **The Linux branch (`mallinfo2`, glibc 2.33 or later) compiles only on Linux and was not run on
  this machine.** `mallinfo2` reports the main arena, which is all this single-threaded benchmark
  uses. Other platforms fail to compile with an explicit `#error`, instead of silently measuring
  nothing.
- The figure counts what the allocator handed out, including its own per-allocation overhead. It
  does not count pages the operating system keeps around afterwards. That is exactly "what this
  structure costs in memory", but it is not the whole process's footprint.

## Entry 55 – `index_query` and `index_memory` now measure exactly what the Java module measures (2026-10-03)

### What was decided
Entry 53 found that this module's `index_query` and Java's measured different things, and gave
opposite rankings. The user decided that both experiments must measure what Java measures, so that
the comparison between languages is as fair as possible. Java's `IndexBenchmark` was read and its
definitions were ported as they are.

### What was done
**`index_query`**, following Java's `IndexBenchmark.query`:
- **Untimed:**
  - build the index of the N books once, and write monolithic, hierarchical, and Mongo if
    reachable;
  - **open each structure once**, the way a running search service would (monolithic parses its
    JSON here, outside the timing);
  - **verify** that every query gets the same answer as from the in-memory index, and throw
    otherwise (Java's `verify`).
- **Timed** (2 warmups + 5 runs): `kDefaultQueryRounds = 100` passes over the 10 queries of
  `shared/queries.txt`, each one tokenized and answered with `query_and`, as Java's `SearchService`
  does.
- **Rows:** 5 `elapsed` (ms, the whole batch of 1,000 queries), then 5 `per_query`
  (µs = elapsed × 1000 / 1000). Same metrics, units and order as Java.
- **Sanity check:** every repetition's answers are added up. If repetitions disagree, it throws,
  and the total also keeps any query from being optimized away.
- `prepare_index_query` (Entry 53) is gone: writing the structures is now part of the experiment's own
  untimed setup.

**`index_memory`**, following Java's `IndexBenchmark.memory`. For each structure (monolithic,
hierarchical, and Mongo if reachable), it reports Java's two metrics:
- `heap_after_build`: memory in use after building the index of the N books and writing it through
  that structure's writer, with the index still alive.
- `heap_after_open`: memory in use after opening the written structure for reading. The reader is a
  postings fetcher (`index_readers.hpp`): monolithic parses the whole JSON, hierarchical keeps only
  its folder path, and Mongo keeps only its client.
- The books are tokenized to their distinct terms **before** anything is measured (Java's
  `tokenizeAll`). The measure is still the allocator's in-use bytes (Entry 54), the counterpart of
  Java's heap used after GC.

Tests rewritten for both experiments: row shape, the `per_query` formula, Mongo rows only when
reachable, the files that `index_query` writes, and the relation between the four memory figures.
Suite total: 178.

### Results on the 200 real books, side by side with Java's `results/real/`
`index_query`, `per_query` in µs (median of 5):

| | N=50 | N=100 | N=200 |
|---|---|---|---|
| monolithic | C++ 2.3 · Java 3.7 | C++ 1.4 · Java 2.0 | C++ 2.6 · Java 4.5 |
| hierarchical | C++ 41.4 · Java 26.0 | C++ 41.2 · Java 31.7 | C++ 61.8 · Java 48.3 |

`index_memory`, in MB:

| | N=50 | N=100 | N=200 |
|---|---|---|---|
| `heap_after_build` monolithic | C++ 16.2 · Java 21.5 | C++ 30.2 · Java 45.0 | C++ 60.5 · Java 97.2 |
| `heap_after_build` hierarchical | C++ 16.2 · Java 0.5 | C++ 30.2 · Java 0.5 | C++ 60.5 · Java 2.1 |
| `heap_after_open` monolithic | C++ 14.4 · Java 18.5 | C++ 25.6 · Java 48.3 | C++ 49.9 · Java 100.3 |
| `heap_after_open` hierarchical | 0 · 0 | 0 · 0 | 0 · 0 |

### Reading the results
- **The ranking now agrees.** Once the structure is open, a monolithic query costs a few µs in both
  languages: a lookup in a parsed map. A hierarchical query costs tens of µs, because every query
  term opens and reads a file. Entry 53's inverted ranking came entirely from timing the JSON parse,
  not from querying.
- **C++ holds roughly half of Java's memory** for the same monolithic index. That is plausible: every
  Java object carries a header, and Java's posting lists box their integers.
- **One real architectural difference, measured rather than hidden.** Java's hierarchical and Mongo
  backends write postings through as books are added and keep almost nothing resident (0.5 to
  2.1 MB). This module builds every structure from one complete in-memory `InvertedIndex`
  (Entries 17-18), so building the hierarchical structure costs as much memory as building the
  monolithic one (60.5 MB at N=200). Once written, opening it costs nothing in either language. This
  belongs in the report's design discussion: the C++ pipeline trades memory during building for a
  single, simple index type shared by every writer.
- **Still not identical:** the machines differ (this Mac with APFS against Java's Linux run with
  NVMe). That may explain why hierarchical queries, dominated by file opens, are somewhat slower
  here. It is the still-open point about running every language on one machine.

## Entry 56 – Parity with Java, step A: `index_build`, `index_update` and `index_disk` under Java's conditions (2026-10-03)

### What was done
The user's goal is that every C++ benchmark runs under the same conditions as the Java module, at the
code level (what is timed, the data, the sizes, the repetitions, the verification, the metrics). The
machine is out of scope. Java's `IndexBenchmark` was read and its rules were ported.
- **New `benchmark/index_benchmark_support`** (shared by all five index experiments), ported from
  Java:
  - `TokenizedBook` and `tokenize_all`: every book is tokenized once, to its distinct terms, before
    anything is timed (Java's `tokenizeAll`);
  - `build_index`;
  - `verify_index`. A written structure must match the in-memory index built from the same books, on
    the postings of every query term and of the first 20 sorted terms of the first and last book, and
    on the answer of every query. A mismatch throws (Java's `verify`).
- **`index_build`**:
  - the tokenizer was **inside** the timed part, and no longer is;
  - the structure's storage is emptied in the untimed reset;
  - the written structure is verified after measuring;
  - new `throughput` rows (books_per_s, with Java's 0.001 ms floor);
  - rows are in Java's order: 5 `elapsed`, then 5 `throughput`.
- **`index_update`**:
  - the tokenizer is no longer timed;
  - the *written* structure is now verified against all N books (before, only the in-memory index
    was checked);
  - rows are in Java's order (5 `elapsed`, then 5 `per_book`);
  - its header comment, stale since Entry 40 ("a full write() per book"), now describes
    `update_terms`.
- **`index_disk`**:
  - new `allocated_bytes`, with Java's own estimate: `allocated_bytes(root)` in `benchmark.hpp` rounds
    every file up to whole filesystem blocks (`statvfs`) and counts one block per directory, root
    included;
  - rows in Java's order (`bytes`, `files`, `allocated_bytes`, `terms`, `postings`);
  - each structure is verified before it is measured.
- **`index_query` and `index_memory`** now use the shared module. `index_memory` also verifies every
  opened structure.
- All five index benchmarks take the query workload (`shared/queries.txt`) for the verification, as
  Java's `IndexBenchmark` does. Suite total: 184.

### Verification on the 200 real books
- **`index_disk` matches Java's `results/real/` exactly** at N=50, 100 and 200 for `bytes` (the
  monolithic JSON included, byte for byte), `files`, `terms` and `postings`. `allocated_bytes`
  differs by exactly 1 block (monolithic) and 2 blocks (hierarchical). Java's structures sit one or
  two folders deeper (`<dir>/datamarts/inverted_index...`), and its estimate counts one block per
  folder. That is an artefact of the path, about 0.0015%.
- **`index_build`** (median of 5 runs; Java from its report):

| | N=50 | N=100 | N=200 | N=200 before this entry |
|---|---|---|---|---|
| monolithic | 158 ms | 579 ms | **936 ms** (Java 1,686) | 2,567 ms |
| hierarchical | 11,052 ms | 16,343 ms | **21,608 ms** (Java 11,339) | 23,566 ms |

  Taking the tokenizer out of the timing, as Java does, cut the monolithic build at N=200 from
  2.6 s to 0.9 s. Most of that cost was tokenizing, not indexing.
- `index_update` was stopped half-way at the user's request, in order to add MongoDB first. It runs
  in the official run.

### An implementation observation (not a condition, so not changed here)
The hierarchical build remains about twice Java's time. Besides the different filesystem, one likely
cause is in this module's own code: `write_text_file` calls `create_directories` for **every** file,
129,356 times for the N=200 index, although there are only ~36 letter folders. It is noted as a
possible optimisation of the implementation. That is exactly what the comparison measures, so it is
not something to hide or adjust in the benchmark.

## Entry 57 – Parity with Java, step B: `JavaRandom`, Java's random generator reproduced number for number (2026-10-03)

### What was done
- `include/stage1/benchmark/java_random.hpp` + `src/benchmark/java_random.cpp`:
  - `JavaRandom(seed)` and `next_int(bound)` reproduce `java.util.Random`, a 48-bit linear
    congruential generator whose algorithm is part of the Java API specification;
  - `java_shuffle(items, random)` reproduces `Collections.shuffle(list, random)` for a random-access
    list.
- Java's rejection loop in `nextInt(bound)` detects biased values through 32-bit `int` overflow
  (`u - r + m < 0`). Signed overflow is undefined behaviour in C++, so the sum is computed in 64 bits
  and compared with `INT_MAX`. The state uses unsigned 64-bit arithmetic masked to 48 bits, which
  wraps exactly like Java's `long`.
- `tests/benchmark/java_random_test.cpp`, 6 tests. **Every expected value was printed by a real JVM**
  (OpenJDK 23, a throwaway `JavaRandomReference.java` outside the repo):
  - `nextInt(100)` and `nextInt(16)` (the power-of-two branch);
  - `nextInt(2^30 + 1)`, where about half of all draws are rejected, which exercises the overflow
    path;
  - a negative seed;
  - `Collections.shuffle` of 1..10;
  - a non-positive bound throws.

  All 6 passed on the first run. Suite total: 190.

### Why
The Java module makes two "random" choices with `new Random(42)`: the order in which
`datalake_lookup` looks books up, and the 1,000 queries of `metadata_query`. C++'s `std::mt19937`,
Python's `random` and Java's generator give three different sequences for the same seed. Each
language would then measure different lookups and queries, which would break the "same data"
condition. Reproducing Java's published algorithm makes the choices identical without sharing any
data file. A short message with an equivalent Python version was prepared for the Python teammate.
It is not used yet: the datalake (step C) and metadata (step D) benchmarks will use it.

## Entry 58 – MongoDB measured for the first time, under Java's conditions (2026-10-03)

### What was done
- **A real MongoDB server on this machine.** The group's `docker-compose.yml` (MongoDB 8.2.12, the
  image Java's results used) was started unchanged. This Mac had no Docker, so the engine comes from
  **Colima** (`brew install colima docker docker-compose`; `colima start --cpu 2 --memory 4`, a
  small Ubuntu VM), then `docker-compose up -d`. The container passes its own healthcheck and answers
  on `localhost:27017`. A Homebrew `mongod` 8.3.11 was also installed but stopped. It was not used,
  so that the server version matches Java's.
- **The Mongo tests ran against a real server for the first time.** Until now they had always been
  skipped. All 4 writer tests and the 2 benchmark ones passed as written.
- **Same conditions as Java's `IndexBenchmark` for the mongo structure:**
  - *Separate databases.* `MongoIndexWriter`, `mongo_postings_fetcher` and the new
    `mongo_disk_usage_bytes` take a database and a collection. They default to SPEC section 6's real
    `search_engine/inverted_index`, but the benchmarks use `search_engine_bench` (Java's
    `BENCH_DATABASE`), and the tests use `search_engine_test`. Before this, the database name was
    hardcoded, so every benchmark and test run overwrote the real index's collection.
  - *Untimed emptying.* The new `MongoIndexWriter::clear()` drops the collection (Java's `clear`),
    and `index_build` calls it in its untimed reset. `write()` still starts with `delete_many`, which
    is now cheap on an empty collection, so the timed part measures building, not clearing.
  - *Disk usage.* `index_disk` now covers mongo with Java's own definition. It requests an `fsync`
    (WiredTiger only moves data into the collection file at each checkpoint), then reports
    `storageSize + totalIndexSize` from `$collStats`, as `bytes`, followed by `terms` and `postings`.
    There are no `files`/`allocated_bytes` rows, since Mongo has no folder of its own, as in Java.
    This closes the gap Entry 42 left open for want of a server to verify against. The BSON numbers
    are read whatever their type (int32, int64 or double).
- 4 new tests: `clear`, the fetcher with a given database, disk usage (positive after writing, 0 for a
  missing collection), and mongo's `index_disk` rows. Suite total: 194, none skipped.
- `cpp/README.md` explains how to start the group's MongoDB, including on a Mac without Docker
  Desktop.

### Verification on the 200 real books
`index_disk` for mongo against Java's `results/real/`:

| N | C++ | Java |
|---|---|---|
| 50 | 3.93 MB | 3.98 MB |
| 100 | 7.34 MB | 7.27 MB |
| 200 | 13.91 MB | 14.13 MB |

`terms` and `postings` are identical to Java's. The bytes differ by under 2%, which is plausible: this
module inserts every document at once (`insert_many`), while Java upserts them with `$addToSet`, and
WiredTiger compresses and fills its pages differently in each case.

### Implementation differences left as they are (they are what is being compared)
- `MongoIndexWriter::update_terms` issues one `update_one` per term, about 7,900 round trips for a
  real book. Java sends a book's terms in a single `bulkWrite`. Expect this module's Mongo
  `index_update` to be slower for that reason; a `bulk_write` would be the natural optimisation.
- Each `write()`/`update_terms()` call opens a new client connection, while Java keeps one per index
  object. That cost is small next to the round trips, but it is inside the timing.
