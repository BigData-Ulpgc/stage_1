#pragma once

#include <filesystem>
#include <string>
#include <unordered_set>
#include <vector>

#include "stage1/benchmark/benchmark.hpp"
#include "stage1/benchmark/sample_books.hpp"

namespace stage1 {

// SPEC section 9's "index_disk" experiment (the course PDF's "Memory and disk
// usage"), under the same conditions as the Java module's IndexBenchmark.disk,
// for monolithic and hierarchical:
//  - Every book is tokenized once (tokenize_all); the index is built, written,
//    and checked with verify_index against `queries` (a mismatch throws).
//  - Rows per structure, in Java's order:
//      bytes            total size of the structure's files
//      files            number of files
//      allocated_bytes  disk space those files and folders reserve, with
//                       Java's estimate (allocated_bytes in benchmark.hpp)
//      terms, postings  the index's logical size, the same for every structure
// `mongo` is left out (Entry 42): its disk usage needs MongoDB's collStats,
// which this machine (no Docker) cannot verify. No timing, no repetitions.
std::vector<BenchmarkResult> benchmark_index_disk(const std::string& language, const std::vector<SampleBook>& books,
                                                    const std::vector<std::string>& queries,
                                                    const std::unordered_set<std::string>& stopwords,
                                                    const std::filesystem::path& output_dir);

}  // namespace stage1
