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
