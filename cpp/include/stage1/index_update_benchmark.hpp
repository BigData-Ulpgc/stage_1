#pragma once

#include <filesystem>
#include <string>
#include <unordered_set>
#include <vector>

#include "stage1/benchmark.hpp"
#include "stage1/sample_books.hpp"

namespace stage1 {

// SPEC section 9's "index_update" experiment (the course PDF's own "update
// performance: cost of adding new books to an existing index without
// rebuilding it completely"). Mirrors the Java module's own methodology: the
// most recent 10% of `books` (at least one) are "added"; the rest already
// form a "base" index, built and persisted once (untimed setup). The timed
// operation adds the "added" books one at a time, each immediately followed
// by a full IndexWriter::write() call -- the only kind of "update" this
// project's writers currently support: none of the three (monolithic,
// hierarchical, mongo) has an incremental write path yet, write() always
// persists the whole current index (Entry 18/25). This experiment exists to
// measure exactly how expensive that is, not to assume an answer. Verifies
// the final in-memory index matches what building from `books` directly
// would produce. Returns 5 "elapsed" rows plus a derived "per_book" row
// (elapsed / k) per structure.
std::vector<BenchmarkResult> benchmark_index_update(const std::string& language,
                                                      const std::vector<SampleBook>& books,
                                                      const std::unordered_set<std::string>& stopwords,
                                                      const std::filesystem::path& output_dir);

}  // namespace stage1
