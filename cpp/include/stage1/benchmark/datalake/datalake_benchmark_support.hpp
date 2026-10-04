#pragma once

#include <filesystem>
#include <memory>
#include <string>

#include "stage1/config/datalake_factory.hpp"
#include "stage1/datalake/datalake.hpp"

namespace stage1 {

// What the datalake experiments share, ported from the Java module's
// DatalakeBenchmark so that both languages run them under the same conditions.
// The structures they compare are kDatalakeStructures (datalake_factory.hpp).

// Java's DatalakeBenchmark.freshDatalake: deletes `dir` and creates an empty
// datalake of `structure` in it through create_datalake. "time" gets its own
// new SimulatedClock::ten_books_per_hour(), so every repetition builds the
// same folders. Always called in an untimed setup. Throws
// std::invalid_argument for a structure not in kDatalakeStructures.
std::unique_ptr<Datalake> fresh_datalake(const std::string& structure, const std::filesystem::path& dir);

}  // namespace stage1
