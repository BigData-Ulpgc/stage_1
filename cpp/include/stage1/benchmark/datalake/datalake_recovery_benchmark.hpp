#pragma once

#include <filesystem>
#include <string>
#include <vector>

#include "stage1/benchmark/benchmark.hpp"
#include "stage1/benchmark/sample_books.hpp"

namespace stage1 {

// SPEC section 9's "datalake_recovery" experiment, as the Java module's
// DatalakeBenchmark.recovery runs it. In the untimed setup of every
// repetition, every book is saved and then the crash is simulated on the books
// at positions 0, 10, 20, ...: each one's body is renamed to body.txt.tmp, what
// a process that died before its final rename would leave. The timed part
// finds the missing books with list_book_ids() and saves them again. Throws
// if, in the final state, a damaged book was not saved again, or any book is
// lost or has more than one complete body. Rows per structure, in Java's
// order: 5 "elapsed" (ms), then one "recovered", "lost" and "duplicates" row
// (books).
std::vector<BenchmarkResult> benchmark_datalake_recovery(const std::string& language,
                                                           const std::vector<SampleBook>& books,
                                                           const std::filesystem::path& output_dir);

}  // namespace stage1
