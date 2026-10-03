#pragma once

#include <filesystem>
#include <string>
#include <vector>

#include "stage1/benchmark/benchmark.hpp"
#include "stage1/benchmark/sample_books.hpp"

namespace stage1 {

// SPEC section 9's "datalake_incremental" experiment, as the Java module's
// DatalakeBenchmark.incremental runs it: the datalake holds the first 90% of
// `books` ("known") and the last 10% ("fresh") are then written too. In the
// untimed setup of every repetition the datalake is rebuilt that way; the
// timed part is list_book_ids() minus the known ids, the one operation every
// layout can do the same way. Throws unless exactly the fresh books are
// detected. Requires at least 2 books. Rows per structure, in Java's order: 5
// "elapsed" (ms), then 5 "detected" (books).
std::vector<BenchmarkResult> benchmark_datalake_incremental(const std::string& language,
                                                              const std::vector<SampleBook>& books,
                                                              const std::filesystem::path& output_dir);

}  // namespace stage1
