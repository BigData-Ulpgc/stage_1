#pragma once

#include <filesystem>
#include <string>
#include <vector>

#include "stage1/benchmark.hpp"
#include "stage1/sample_books.hpp"

namespace stage1 {

// SPEC section 9's "metadata_insert" experiment (section 4's own "insertion
// speed"): before each repetition (untimed), opens a fresh, empty
// MetadataStore; the timed part is only "extract metadata from every book's
// header and insert it" -- opening the database and creating its schema is a
// one-time cost, not part of what "insertion speed" means. Verifies every
// book ended up present after the final repetition. `measure_elapsed_ms`'s
// default 2+5 repetitions. `structure` is always "sqlite": this project has
// only one metadata backend (see DEVLOG entry 13's reasoning for not
// generalizing MetadataStore without a second real implementation), unlike
// the Java module, which also compares "sqlite" against "sqlite_no_index".
// Returns 5 "elapsed" rows plus 5 derived "throughput" rows (rows/s).
std::vector<BenchmarkResult> benchmark_metadata_insert(const std::string& language,
                                                         const std::vector<SampleBook>& books,
                                                         const std::filesystem::path& output_dir);

}  // namespace stage1
