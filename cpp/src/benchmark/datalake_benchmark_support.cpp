#include "stage1/benchmark/datalake_benchmark_support.hpp"

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

}  // namespace stage1
