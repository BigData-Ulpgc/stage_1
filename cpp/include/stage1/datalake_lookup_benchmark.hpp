#pragma once

#include <filesystem>
#include <string>
#include <vector>

#include "stage1/benchmark.hpp"
#include "stage1/sample_books.hpp"

namespace stage1 {

// SPEC section 9's "datalake_lookup" experiment (section 3's own "lookup
// cost"): for each of the three required layouts, first writes every book in
// `books` (untimed setup), then times calling locate() for every book id,
// `measure_elapsed_ms`'s default 2+5 repetitions. For `time`, locate() only
// ever finds books through the *same* Datalake instance that wrote them (see
// TimeBasedDatalake::locate), so this experiment, by construction, cannot
// show the "fresh process, nothing remembered" case -- see DEVLOG. Returns 5
// BenchmarkResult rows per structure.
std::vector<BenchmarkResult> benchmark_datalake_lookup(const std::string& language,
                                                         const std::vector<SampleBook>& books,
                                                         const std::filesystem::path& output_dir);

}  // namespace stage1
