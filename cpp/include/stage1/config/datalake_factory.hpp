#pragma once

#include <filesystem>
#include <memory>
#include <string>
#include <vector>

#include "stage1/datalake/datalake.hpp"
#include "stage1/datalake/time_based_datalake.hpp"

namespace stage1 {

// The datalake layouts of SPEC section 3, in the order of Java's
// DatalakeFactory.NAMES (also the order of the benchmark CSV rows).
inline const std::vector<std::string> kDatalakeStructures = {"book", "range", "time"};

// Java's DatalakeFactory.create(name, root, clock): the only place that turns
// a structure name into a Datalake class. `clock` is only used by "time",
// which keeps it alive for as long as the datalake lives; "book" and "range"
// ignore it. The pipeline passes a SystemClock, the benchmarks a
// SimulatedClock. Throws std::invalid_argument for an unknown structure, or
// for "time" without a clock.
std::unique_ptr<Datalake> create_datalake(const std::string& structure, const std::filesystem::path& root,
                                          std::unique_ptr<Clock> clock);

}  // namespace stage1
