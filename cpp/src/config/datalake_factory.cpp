#include "stage1/config/datalake_factory.hpp"

#include <stdexcept>

#include "stage1/datalake/book_based_datalake.hpp"
#include "stage1/datalake/range_based_datalake.hpp"

namespace stage1 {

namespace {

// Holds the clock of an OwningTimeBasedDatalake. It is a separate base class
// only so that it is built first: base classes are constructed in the order
// they are listed, so the clock exists before TimeBasedDatalake stores a
// reference to it, and is destroyed after it.
struct ClockOwner {
    std::unique_ptr<Clock> clock;
};

// A TimeBasedDatalake that owns its clock, so create_datalake can hand it out
// as a plain std::unique_ptr<Datalake> with nothing else to keep alive.
class OwningTimeBasedDatalake : private ClockOwner, public TimeBasedDatalake {
public:
    OwningTimeBasedDatalake(const std::filesystem::path& root, std::unique_ptr<Clock> owned)
        : ClockOwner{std::move(owned)}, TimeBasedDatalake(root, *clock) {}
};

}  // namespace

std::unique_ptr<Datalake> create_datalake(const std::string& structure, const std::filesystem::path& root,
                                          std::unique_ptr<Clock> clock) {
    if (structure == "book") {
        return std::make_unique<BookBasedDatalake>(root);
    }
    if (structure == "range") {
        return std::make_unique<RangeBasedDatalake>(root);
    }
    if (structure == "time") {
        if (!clock) {
            throw std::invalid_argument("the time datalake needs a clock");
        }
        return std::make_unique<OwningTimeBasedDatalake>(root, std::move(clock));
    }
    throw std::invalid_argument("unknown datalake structure: " + structure);
}

}  // namespace stage1
