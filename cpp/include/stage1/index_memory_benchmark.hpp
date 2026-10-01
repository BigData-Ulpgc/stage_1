#pragma once

#include <filesystem>
#include <string>
#include <unordered_set>
#include <vector>

#include "stage1/benchmark.hpp"
#include "stage1/sample_books.hpp"

namespace stage1 {

// SPEC section 9's "index_memory" experiment (the course PDF's own "Memory
// and disk usage: Amount of RAM... required by each implementation").
//
// C++ has no garbage collector or heap-size introspection API (what the Java
// module uses: Runtime.totalMemory()/freeMemory() after forcing a GC). The
// closest honest equivalent is the operating system's own peak resident set
// size (RSS, via POSIX getrusage -- available on macOS and Linux, this
// project's only targets), measured before and after the step being
// costed. RSS is a *monotonic peak* for the whole process, not a per-object
// counter: the delta reported is "how much higher the process's memory peak
// grew doing this step", which undercounts a step that happens not to
// exceed a peak an earlier, larger step already set. Single-shot (no
// repetitions: RSS is a point-in-time OS counter, not something warmup/
// averaging applies to).
//
// Two things are measured:
//   in_memory_index   building the shared InvertedIndex from `books` (what
//                     every structure's in-memory representation starts as)
//   monolithic        on top of that, parsing the already-written monolithic
//                     JSON file back into memory (what a query service would
//                     keep resident to answer queries fast, see Entry 28)
// `hierarchical` and `mongo` are deliberately not measured here: neither has
// an equivalent "loaded into this process" state in this project's design
// (hierarchical reads small files on demand with nothing kept resident;
// mongo's data lives in the database server's own process, not this one,
// the same limitation the Java module's own comment notes for its client).
std::vector<BenchmarkResult> benchmark_index_memory(const std::string& language,
                                                      const std::vector<SampleBook>& books,
                                                      const std::unordered_set<std::string>& stopwords,
                                                      const std::filesystem::path& output_dir);

}  // namespace stage1
