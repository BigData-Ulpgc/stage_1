#pragma once

#include <cstddef>
#include <filesystem>
#include <string>
#include <vector>

#include "stage1/benchmark/benchmark.hpp"
#include "stage1/benchmark/metadata/metadata_benchmark_support.hpp"

namespace stage1 {

// SPEC section 9's "metadata_insert" experiment, as the Java module's
// MetadataBenchmark.insert runs it, on already built rows (no header is
// parsed: building the metadata is not part of the timing). The rows are split
// into batches of `batch_size` before anything is timed. For each variant
// (sqlite, sqlite_no_index), the untimed setup of every repetition opens an
// empty database; the timed part inserts every batch with insert_books (one
// transaction per batch). Throws unless the table ends up with every row. Rows
// per variant, in Java's order: 5 "elapsed" (ms), then 5 "throughput"
// (rows_per_s). The databases go to `output_dir`/insert/<variant>_<N>.db.
std::vector<BenchmarkResult> benchmark_metadata_insert(const std::string& language,
                                                         const std::vector<StoredBook>& rows,
                                                         const std::filesystem::path& output_dir,
                                                         std::size_t batch_size = kMetadataBatchSize);

}  // namespace stage1
