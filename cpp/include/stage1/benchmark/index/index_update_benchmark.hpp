#pragma once

#include <filesystem>
#include <string>
#include <unordered_set>
#include <vector>

#include "stage1/benchmark/benchmark.hpp"
#include "stage1/benchmark/sample_books.hpp"

namespace stage1 {

// SPEC section 9's "index_update" experiment (the course PDF's "cost of adding
// new books to an existing index without rebuilding it completely"), under
// the same conditions as the Java module's IndexBenchmark.update, for each
// structure (monolithic, hierarchical, and mongo if reachable):
//  - k = 10% of the books (at least 1) are "added"; the other N-k form the
//    existing index.
//  - Untimed, once: every book is tokenized (tokenize_all).
//  - Untimed, before each repetition: the N-k base books are built into a
//    fresh index and written, as a later pipeline run would find them.
//  - Timed (N_WARMUP=2, N_RUNS=5): the k books, one at a time, each added to
//    the index and persisted with IndexWriter::update_terms for its own terms
//    (Java: addDocument + flush per book). Monolithic has no cheaper path and
//    rewrites its whole file; hierarchical and mongo touch only those terms.
//  - Afterwards: the written structure is opened and checked with
//    verify_index against all N books (N-k + k == building N); a mismatch
//    throws.
// Rows per structure, in Java's order: 5 `elapsed` (ms), then 5 `per_book`
// (ms = elapsed / k). Throws std::invalid_argument with fewer than 2 books.
std::vector<BenchmarkResult> benchmark_index_update(const std::string& language,
                                                      const std::vector<SampleBook>& books,
                                                      const std::vector<std::string>& queries,
                                                      const std::unordered_set<std::string>& stopwords,
                                                      const std::filesystem::path& output_dir);

}  // namespace stage1
