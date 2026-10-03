#include "stage1/benchmark/datalake_benchmark_support.hpp"

#include <algorithm>
#include <stdexcept>

#include "stage1/benchmark/simulated_clock.hpp"
#include "stage1/datalake/book_based_datalake.hpp"
#include "stage1/datalake/range_based_datalake.hpp"
#include "stage1/datalake/time_based_datalake.hpp"

namespace stage1 {

namespace {

// Holds the clock of a SimulatedTimeDatalake. It is a separate base class
// only so that it is built first: base classes are constructed in the order
// they are listed, so the clock exists before TimeBasedDatalake stores a
// reference to it, and is destroyed after it.
struct SimulatedClockHolder {
    SimulatedClock clock = SimulatedClock::ten_books_per_hour();
};

// A TimeBasedDatalake that owns its SimulatedClock, so fresh_datalake can
// hand it out as a plain std::unique_ptr<Datalake> with nothing else to keep
// alive.
class SimulatedTimeDatalake : private SimulatedClockHolder, public TimeBasedDatalake {
public:
    explicit SimulatedTimeDatalake(const std::filesystem::path& root) : TimeBasedDatalake(root, clock) {}
};

}  // namespace

std::unique_ptr<Datalake> fresh_datalake(const std::string& structure, const std::filesystem::path& dir) {
    if (structure != "book" && structure != "range" && structure != "time") {
        throw std::invalid_argument("unknown datalake structure: " + structure);
    }
    std::filesystem::remove_all(dir);
    if (structure == "book") {
        return std::make_unique<BookBasedDatalake>(dir);
    }
    if (structure == "range") {
        return std::make_unique<RangeBasedDatalake>(dir);
    }
    return std::make_unique<SimulatedTimeDatalake>(dir);
}

std::vector<BenchmarkResult> elapsed_rows(const std::string& language, const std::string& experiment,
                                          const std::string& structure, int dataset_size,
                                          const std::vector<double>& elapsed_ms) {
    std::vector<BenchmarkResult> rows;
    int repetition = 1;
    for (double ms : elapsed_ms) {
        rows.push_back(BenchmarkResult{language, experiment, structure, dataset_size, repetition++, "elapsed", ms, "ms"});
    }
    return rows;
}

std::vector<BenchmarkResult> derived_rows(const std::vector<BenchmarkResult>& elapsed, const std::string& metric,
                                          const std::string& unit, const std::function<double(double)>& from_ms) {
    std::vector<BenchmarkResult> rows;
    for (const auto& row : elapsed) {
        BenchmarkResult derived = row;  // same language, experiment, structure, size and repetition
        derived.metric = metric;
        derived.value = from_ms(std::max(row.value, 0.001));
        derived.unit = unit;
        rows.push_back(derived);
    }
    return rows;
}

}  // namespace stage1
