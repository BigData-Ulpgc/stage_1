#pragma once

#include <filesystem>
#include <string>
#include <unordered_set>
#include <vector>

#include "stage1/benchmark.hpp"
#include "stage1/sample_books.hpp"

namespace stage1 {

// SPEC section 9's "index_disk" experiment (the course PDF's own "Memory and
// disk usage"): for `monolithic` and `hierarchical`, builds the index once
// and writes it, then reports `bytes` (total file size on disk) and `files`
// (file count). Also reports `terms` and `postings` -- the index's *logical*
// size, identical for every structure by construction (same vocabulary, same
// postings), making it easy to see in the same CSV that only the physical
// representation differs, not the data. `mongo` is left out: reading its real
// disk usage needs MongoDB's own `collStats` command, whose numeric BSON
// types need careful, untested-here handling (this machine has no Docker to
// verify it against), the same "don't ship what can't be verified now"
// discipline already applied elsewhere (e.g. `allocated_bytes`, Entry 35).
// No timing involved; no repetitions.
std::vector<BenchmarkResult> benchmark_index_disk(const std::string& language, const std::vector<SampleBook>& books,
                                                    const std::unordered_set<std::string>& stopwords,
                                                    const std::filesystem::path& output_dir);

}  // namespace stage1
