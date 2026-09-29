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
