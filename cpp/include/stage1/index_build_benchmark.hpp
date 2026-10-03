#pragma once

#include <filesystem>
#include <string>
#include <unordered_set>
#include <vector>

#include "stage1/benchmark.hpp"

namespace stage1 {

// An already-downloaded, already header/body-split book, ready to feed a
// benchmark. SPEC section 9: benchmarks that build/write structures must
// start from books already on disk (e.g. sample_dataset/), so the network
// never contaminates the timings; loading these is the caller's job, this
// struct is just the shape the benchmark needs.
struct SampleBook {
    int book_id;
    std::string body;
};

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
