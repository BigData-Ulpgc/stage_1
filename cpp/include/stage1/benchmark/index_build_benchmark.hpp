#pragma once

#include <filesystem>
#include <string>
#include <unordered_set>
#include <vector>

#include "stage1/benchmark/benchmark.hpp"
#include "stage1/benchmark/sample_books.hpp"

namespace stage1 {

// SPEC section 9's "index_build" experiment: for each required index
// structure (monolithic, hierarchical, and mongo if reachable -- skipped
// otherwise, not failed), times building a fresh InvertedIndex from `books`
// (tokenizing each body with `stopwords`) and persisting it through that
// structure's IndexWriter, using measure_elapsed_ms's defaults (N_WARMUP=2,
// N_RUNS=5). Returns 5 BenchmarkResult rows per structure that ran, ready for
// write_benchmark_results. Each structure writes under its own subdirectory
// of `output_dir`.
std::vector<BenchmarkResult> benchmark_index_build(const std::string& language, const std::vector<SampleBook>& books,
                                                     const std::unordered_set<std::string>& stopwords,
                                                     const std::filesystem::path& output_dir);

}  // namespace stage1
