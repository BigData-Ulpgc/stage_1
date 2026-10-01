#pragma once

#include <filesystem>
#include <string>
#include <vector>

#include "stage1/benchmark.hpp"
#include "stage1/sample_books.hpp"

namespace stage1 {

// SPEC section 9's "datalake_storage" experiment (section 3's own "storage
// overhead"): for each layout, writes every book in `books` once, then
// reports how the result looks on disk -- no timing involved, this
// experiment measures size, not speed. One BenchmarkResult row per structure
// per metric:
//   files                count of regular files
//   directories           count of directories (the root itself not counted)
//   max_entries_per_dir   entries in the single most populated directory,
//                         root included -- the number SPEC section 3 directly
//                         warns about ("a very large number of small files
//                         can overwhelm the filesystem")
//   bytes                 logical size of every file, summed
// Mirrors the Java module's own DatalakeStats shape, minus its block-size-
// rounded "allocated_bytes" estimate: there is no portable standard-library
// way to query a filesystem's block size in C++, and Java's own comment
// already calls that number an estimate -- see DEVLOG for the full reasoning.
std::vector<BenchmarkResult> benchmark_datalake_storage(const std::string& language,
                                                          const std::vector<SampleBook>& books,
                                                          const std::filesystem::path& output_dir);

}  // namespace stage1
