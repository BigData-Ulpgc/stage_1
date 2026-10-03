#pragma once

#include <filesystem>
#include <string>
#include <vector>

#include "stage1/benchmark/benchmark.hpp"
#include "stage1/benchmark/sample_books.hpp"

namespace stage1 {

// SPEC section 9's "datalake_write" experiment (section 3's own "download
// and write throughput"): for each of the three required datalake layouts
// (book, range, time), times writing every book in `books` (header + body)
// to a fresh copy of that layout, using measure_elapsed_ms's defaults
// (N_WARMUP=2, N_RUNS=5). Returns 5 BenchmarkResult rows per structure.
// Each structure writes under its own subdirectory of `output_dir`.
std::vector<BenchmarkResult> benchmark_datalake_write(const std::string& language,
                                                        const std::vector<SampleBook>& books,
                                                        const std::filesystem::path& output_dir);

}  // namespace stage1
