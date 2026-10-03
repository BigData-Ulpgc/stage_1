#pragma once

#include <filesystem>
#include <string>
#include <unordered_set>
#include <vector>

#include "stage1/benchmark/benchmark.hpp"
#include "stage1/benchmark/sample_books.hpp"

namespace stage1 {

// SPEC section 9's "index_build" experiment, under the same conditions as the
// Java module's IndexBenchmark.build, for each structure (monolithic,
// hierarchical, and mongo if reachable):
//  - Untimed, once: every book is tokenized (tokenize_all), so the tokenizer
//    is in no timing.
//  - Untimed, before each repetition: the structure's storage is emptied.
//  - Timed (N_WARMUP=2, N_RUNS=5): building the in-memory index from the
//    tokenized books and writing it through the structure's IndexWriter
//    (Java: addDocument for every book + one flush).
//  - Afterwards: the written structure is opened and checked with
//    verify_index against `queries`; a mismatch throws.
// Rows per structure, in Java's order: 5 `elapsed` (ms), then 5 `throughput`
// (books_per_s = N / elapsed in seconds). Each structure writes under its own
// subdirectory of `output_dir`.
std::vector<BenchmarkResult> benchmark_index_build(const std::string& language, const std::vector<SampleBook>& books,
                                                     const std::vector<std::string>& queries,
                                                     const std::unordered_set<std::string>& stopwords,
                                                     const std::filesystem::path& output_dir);

}  // namespace stage1
