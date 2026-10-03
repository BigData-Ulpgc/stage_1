#pragma once

#include <filesystem>
#include <memory>
#include <string>
#include <vector>

#include "stage1/datalake/datalake.hpp"

namespace stage1 {

// What the datalake experiments share, ported from the Java module's
// DatalakeBenchmark so that both languages run them under the same conditions.

// The layouts SPEC section 3 compares, in the order of Java's
// DatalakeFactory.NAMES (the order of the CSV rows).
inline const std::vector<std::string> kDatalakeStructures = {"book", "range", "time"};

// Java's DatalakeBenchmark.freshDatalake: deletes `dir` and creates an empty
// datalake of `structure` in it. "time" gets its own new
// SimulatedClock::ten_books_per_hour(), so every repetition builds the same
// folders. Always called in an untimed setup. Throws std::invalid_argument
// for a structure not in kDatalakeStructures.
std::unique_ptr<Datalake> fresh_datalake(const std::string& structure, const std::filesystem::path& dir);

}  // namespace stage1
