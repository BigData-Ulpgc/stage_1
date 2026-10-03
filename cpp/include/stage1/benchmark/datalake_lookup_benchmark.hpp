#pragma once

#include <filesystem>
#include <string>
#include <vector>

#include "stage1/benchmark/benchmark.hpp"
#include "stage1/benchmark/sample_books.hpp"

namespace stage1 {

// SPEC section 9's "datalake_lookup" experiment, as the Java module's
// DatalakeBenchmark.lookup runs it: for each layout, writes every book once
// (untimed), then times locate() for every id, in a fixed "random" order
// (Java's Collections.shuffle with seed 42, reproduced by JavaRandom), 2
// warmups + 5 measured. Throws if a book is not found. For "time", locate()
// only finds books through the instance that wrote them (see
// TimeBasedDatalake::locate), while Java's scans the disk: a design
// difference, see DEVLOG Entries 31 and 61. Rows per structure, in Java's
// order: 5 "elapsed" (ms), then 5 "per_lookup" (us).
std::vector<BenchmarkResult> benchmark_datalake_lookup(const std::string& language,
                                                         const std::vector<SampleBook>& books,
                                                         const std::filesystem::path& output_dir);

}  // namespace stage1
