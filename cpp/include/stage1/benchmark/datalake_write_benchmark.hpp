#pragma once

#include <filesystem>
#include <string>
#include <vector>

#include "stage1/benchmark/benchmark.hpp"
#include "stage1/benchmark/sample_books.hpp"

namespace stage1 {

// SPEC section 9's "datalake_write" experiment, as the Java module's
// DatalakeBenchmark.write runs it: for each layout (book, range, time), times
// writing every book in `books` (header + body) into an empty datalake. The
// folder is emptied in the untimed setup of every repetition (2 warmups + 5
// measured), and "time" uses a simulated clock of 10 books per hour. Rows per
// structure, in Java's order: 5 "elapsed" (ms), then 5 "throughput"
// (books_per_s). Each structure writes under `output_dir`/<structure>.
std::vector<BenchmarkResult> benchmark_datalake_write(const std::string& language,
                                                        const std::vector<SampleBook>& books,
                                                        const std::filesystem::path& output_dir);

}  // namespace stage1
