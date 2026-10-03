#pragma once

#include <filesystem>
#include <string>
#include <vector>

#include "stage1/benchmark/benchmark.hpp"
#include "stage1/benchmark/metadata_benchmark_support.hpp"

namespace stage1 {

// SPEC section 9's "metadata_query" experiment, as the Java module's
// MetadataBenchmark.query runs it. The workload is `query_count` rows picked
// with Java's new Random(42) (reproduced by JavaRandom), so both languages
// ask exactly the same queries; every value exists, so every query finds
// something. For each variant (sqlite, sqlite_no_index), every row is inserted
// once (untimed), then each query type -- find_by_id, find_by_author,
// find_by_title -- is timed over the whole workload, 2 warmups + 5 measured.
// Throws if a query finds nothing, or a row has no title or author. Rows per
// variant and query type, in Java's order, for each repetition: "<type>" (ms,
// the whole workload), then "<type>_avg" (us per query). The databases go to
// `output_dir`/query/<variant>_<N>.db.
std::vector<BenchmarkResult> benchmark_metadata_query(const std::string& language,
                                                        const std::vector<StoredBook>& rows,
                                                        const std::filesystem::path& output_dir,
                                                        int query_count = kMetadataQueries);

}  // namespace stage1
