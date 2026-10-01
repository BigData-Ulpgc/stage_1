#pragma once

#include <filesystem>
#include <string>
#include <vector>

#include "stage1/benchmark.hpp"
#include "stage1/sample_books.hpp"

namespace stage1 {

// SPEC section 9's "datalake_incremental" experiment (section 3's own
// "incremental processing"): the datalake already holds 90% of `books`
// ("known"); the remaining, most-recent 10% ("fresh") then get written too,
// simulating new arrivals. For each layout, times calling list_book_ids()
// and subtracting the known ids, i.e. the one operation the Datalake
// contract lets every layout do the same way (no external bookkeeping),
// `measure_elapsed_ms`'s default 2+5 repetitions. Mirrors the Java module's
// own `DatalakeBenchmark.incremental` methodology, so results stay
// comparable across languages. Requires at least 2 books. Returns 5
// BenchmarkResult rows per structure.
std::vector<BenchmarkResult> benchmark_datalake_incremental(const std::string& language,
                                                              const std::vector<SampleBook>& books,
                                                              const std::filesystem::path& output_dir);

}  // namespace stage1
