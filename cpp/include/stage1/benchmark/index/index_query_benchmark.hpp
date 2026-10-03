#pragma once

#include <filesystem>
#include <string>
#include <unordered_set>
#include <vector>

#include "stage1/benchmark/benchmark.hpp"
#include "stage1/benchmark/sample_books.hpp"

namespace stage1 {

// How many times the whole query workload runs inside one measurement: 10
// queries alone take microseconds, too short to time reliably. The same value
// as the Java module's IndexBenchmark.DEFAULT_QUERY_ROUNDS.
inline constexpr int kDefaultQueryRounds = 100;

// SPEC section 9's "index_query" experiment, measured exactly as the Java
// module's IndexBenchmark.query does, so the two can be compared directly:
//
//  - Untimed: builds the index of `books` once, writes every available
//    structure under `index_dir` (monolithic, hierarchical, and mongo if
//    reachable), then opens each one for reading, once, as a running search
//    service would. For monolithic, opening means parsing the whole JSON.
//    Every opened structure must answer each query exactly like the
//    in-memory index; if one does not, this throws instead of producing
//    numbers.
//  - Timed (N_WARMUP=2, N_RUNS=5): `query_rounds` times the whole query
//    workload. Each query is tokenized with `stopwords` and answered with
//    query_and, as Java's SearchService does.
//
// Rows per structure, in Java's order: 5 `elapsed` (ms, the whole batch), then
// 5 `per_query` (µs: elapsed / (query_rounds * queries.size())).
std::vector<BenchmarkResult> benchmark_index_query(const std::string& language, const std::vector<SampleBook>& books,
                                                     const std::vector<std::string>& queries,
                                                     const std::unordered_set<std::string>& stopwords,
                                                     const std::filesystem::path& index_dir,
                                                     int query_rounds = kDefaultQueryRounds);

}  // namespace stage1
