#pragma once

#include <filesystem>
#include <string>
#include <vector>

#include "stage1/benchmark/benchmark.hpp"
#include "stage1/benchmark/sample_books.hpp"

namespace stage1 {

// SPEC section 9's "metadata_query" experiment (section 4's own "query
// performance"): the database is populated once (untimed). A fixed-seed
// workload of `query_count` random picks from `books` (repeats allowed,
// mirroring the Java module's own methodology) feeds three query types --
// find_by_id, find_by_author, find_by_title -- each run as one timed block of
// `query_count` lookups per `measure_elapsed_ms` repetition (2+5 default), so
// "elapsed" is the total time for the whole workload. Each repetition also
// gets a derived "<type>_avg" row: microseconds per single lookup. Every
// query must find at least one result (verified, throws otherwise). Requires
// every book to have both a title and an author (throws otherwise: the
// author/title workloads need something to query for). `structure` is
// "sqlite" (see Entry 36/37's reasoning for not comparing a second backend).
std::vector<BenchmarkResult> benchmark_metadata_query(const std::string& language,
                                                        const std::vector<SampleBook>& books,
                                                        const std::filesystem::path& output_dir,
                                                        int query_count = 1000);

}  // namespace stage1
