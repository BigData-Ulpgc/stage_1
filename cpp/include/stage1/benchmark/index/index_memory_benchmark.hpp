#pragma once

#include <filesystem>
#include <string>
#include <unordered_set>
#include <vector>

#include "stage1/benchmark/benchmark.hpp"
#include "stage1/benchmark/sample_books.hpp"

namespace stage1 {

// SPEC section 9's "index_memory" experiment, measured as the Java module's
// IndexBenchmark.memory does, with the same two metrics, for each structure
// (monolithic, hierarchical, and mongo if reachable):
//
//   heap_after_build  memory in use after building the index of `books` and
//                     writing it through that structure's writer, with the
//                     index still alive
//   heap_after_open   memory in use after opening the written structure for
//                     reading, as a running search service would. Monolithic
//                     parses its whole JSON; hierarchical keeps nothing but its
//                     folder path (it reads term files on demand); mongo keeps
//                     only the client (the data lives in mongod)
//
// Measure: the bytes the allocator reports in use, before and after each step
// (macOS malloc_zone_statistics, Linux glibc mallinfo2), the C++ counterpart of
// the JVM heap Java reads after a GC (DEVLOG Entries 54-55). The books are
// tokenized before any measurement (tokenize_all), and each opened structure
// is checked with verify_index against `queries` afterwards, as Java does.
//
// One real difference from Java, reported as is: Java's hierarchical and mongo
// backends write postings through and keep almost nothing in memory, while
// this module builds every structure from one complete in-memory
// InvertedIndex (DEVLOG Entries 17-18), so heap_after_build is the same for
// all three structures here.
std::vector<BenchmarkResult> benchmark_index_memory(const std::string& language,
                                                      const std::vector<SampleBook>& books,
                                                      const std::vector<std::string>& queries,
                                                      const std::unordered_set<std::string>& stopwords,
                                                      const std::filesystem::path& output_dir);

}  // namespace stage1
