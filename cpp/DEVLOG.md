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
