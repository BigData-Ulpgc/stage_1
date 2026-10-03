#pragma once

#include <filesystem>
#include <functional>
#include <memory>
#include <string>
#include <vector>

#include "stage1/benchmark/benchmark.hpp"
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

// One "elapsed" row (ms) per measured repetition, numbered from 1.
std::vector<BenchmarkResult> elapsed_rows(const std::string& language, const std::string& experiment,
                                          const std::string& structure, int dataset_size,
                                          const std::vector<double>& elapsed_ms);

// Java's DatalakeBenchmark.derived: for every "elapsed" row, a `metric` row
// with the same repetition and value from_ms(ms). Times under 0.001 ms count
// as 0.001 ms, so a rate never divides by zero.
std::vector<BenchmarkResult> derived_rows(const std::vector<BenchmarkResult>& elapsed, const std::string& metric,
                                          const std::string& unit, const std::function<double(double)>& from_ms);

}  // namespace stage1
