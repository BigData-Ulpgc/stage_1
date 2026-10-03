#pragma once

#include <filesystem>
#include <string>
#include <unordered_set>
#include <vector>

#include "stage1/benchmark/benchmark.hpp"
#include "stage1/benchmark/sample_books.hpp"

namespace stage1 {

// SPEC section 9's "index_query" experiment: for each required structure
// that benchmark_index_build already wrote under `index_dir` (monolithic,
// hierarchical, and mongo if reachable), times loading that structure fresh
// and then answering every query in `queries` (tokenized with `stopwords`,
// combined with the same AND semantics as query_and) against it directly --
// not through the shared in-memory InvertedIndex, which would make every
// structure score identically and defeat the point of comparing them.
//
// Loading and the whole query batch are timed together, as one unit per
// measure_elapsed_ms repetition (N_WARMUP=2, N_RUNS=5): a deliberately
// "cold" measurement, the same for all three structures, so the comparison
// stays fair even though a real query service would normally keep a
// structure loaded across many queries. `dataset_size` is the number of
// books the index was originally built from (informational only).
std::vector<BenchmarkResult> benchmark_index_query(const std::string& language, int dataset_size,
                                                     const std::vector<std::string>& queries,
                                                     const std::unordered_set<std::string>& stopwords,
                                                     const std::filesystem::path& index_dir);

// Builds the index of `books` once and writes it, untimed, to exactly the
// places benchmark_index_query reads under `index_dir`: monolithic,
// hierarchical, and mongo if reachable. This lets index_query run on its
// own, without a whole benchmark_index_build, whose 7 timed repetitions
// would only write the same files over and over.
void prepare_index_query(const std::vector<SampleBook>& books, const std::unordered_set<std::string>& stopwords,
                         const std::filesystem::path& index_dir);

}  // namespace stage1
