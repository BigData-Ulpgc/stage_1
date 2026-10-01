#pragma once

#include <filesystem>
#include <string>
#include <vector>

#include "stage1/benchmark.hpp"
#include "stage1/sample_books.hpp"

namespace stage1 {

// SPEC section 9's "datalake_recovery" experiment (section 3's own "recovery
// behavior"): simulates a crash by writing every book, then deleting the
// header file of every 10th one -- exactly the gap "write first, mark after"
// (DEVLOG, before Phase 8) is meant to catch, since write_text_file writes a
// book's body and header as two separate steps, not atomically. Before each
// repetition (untimed), the damage is freshly reintroduced; only "find what
// is missing and rewrite it" is timed, `measure_elapsed_ms`'s default 2+5
// repetitions. Mirrors the Java module's own damage-every-10th-book
// methodology. Throws if, after the final repetition, any book is still
// missing or any structure ends up with more body files than books (lost or
// duplicated data). Requires at least 10 books (so "every 10th" damages at
// least one). Returns, per structure: 5 "elapsed" rows plus one "recovered"
// and one "duplicates" row (both in "books").
std::vector<BenchmarkResult> benchmark_datalake_recovery(const std::string& language,
                                                           const std::vector<SampleBook>& books,
                                                           const std::filesystem::path& output_dir);

}  // namespace stage1
