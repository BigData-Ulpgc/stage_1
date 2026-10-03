#pragma once

#include <filesystem>
#include <string>
#include <unordered_set>
#include <vector>

#include "stage1/benchmark/benchmark.hpp"
#include "stage1/benchmark/sample_books.hpp"

namespace stage1 {

// SPEC section 9's "index_memory" experiment (the course PDF's own "Memory
// and disk usage: Amount of RAM... required by each implementation").
//
// Measure: the bytes the memory allocator reports as in use (allocated and
// not yet freed) right before and right after the step, while what it built
// is still alive. The difference is the memory that structure holds: the
// C++ counterpart of the heap usage the Java module reads from its JVM.
// macOS: malloc_zone_statistics (all zones); Linux: glibc's mallinfo2.
//
// Until DEVLOG Entry 54 this used getrusage's ru_maxrss, the *peak* resident
// memory of the whole process. That proved unusable for comparing sizes: a
// peak never goes down, a step that reuses freed memory does not move it,
// and macOS compresses idle pages. The same N=200 build measured 89.3 MB in
// one run and 22.8 MB in another, while in-use bytes give 60.5 MB every
// time, in any order, in one process. Single-shot (no repetitions: the
// figure is a byte count, not a timing).
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
