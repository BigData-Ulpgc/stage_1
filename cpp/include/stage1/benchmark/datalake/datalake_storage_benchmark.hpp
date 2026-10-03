#pragma once

#include <filesystem>
#include <string>
#include <vector>

#include "stage1/benchmark/benchmark.hpp"
#include "stage1/benchmark/sample_books.hpp"

namespace stage1 {

// SPEC section 9's "datalake_storage" experiment, as the Java module's
// DatalakeBenchmark.storage runs it: for each layout, writes every book in
// `books` into an empty datalake ("time" with the simulated clock of 10 books
// per hour), then reports how it looks on disk. No timing: it measures size,
// not speed. One row per structure per metric, in Java's order:
//   files                count of regular files
//   directories          count of directories (the root itself not counted)
//   max_entries_per_dir  entries in the most populated directory, root
//                        included -- the number SPEC section 3 warns about
//                        ("a very large number of small files can overwhelm
//                        the filesystem")
//   bytes                logical size of every file, summed
//   allocated_bytes      space the disk reserves, with Java's whole-block
//                        estimate (allocated_bytes() in benchmark.hpp)
std::vector<BenchmarkResult> benchmark_datalake_storage(const std::string& language,
                                                          const std::vector<SampleBook>& books,
                                                          const std::filesystem::path& output_dir);

}  // namespace stage1
