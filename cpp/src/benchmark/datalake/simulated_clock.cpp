#include "stage1/benchmark/datalake/simulated_clock.hpp"

#include <ctime>

namespace stage1 {

SimulatedClock SimulatedClock::ten_books_per_hour() {
    std::tm start{};
    start.tm_year = 2026 - 1900;  // std::tm counts years from 1900
    start.tm_mon = 0;             // and months from 0 (January)
    start.tm_mday = 1;
    start.tm_isdst = -1;  // let mktime work out whether DST applies
    // mktime reads `start` as a local time, the zone time_folder_name uses.
    const auto midnight = std::chrono::system_clock::from_time_t(std::mktime(&start));
    return SimulatedClock(midnight, std::chrono::minutes(6));
}

std::chrono::system_clock::time_point SimulatedClock::now() const {
    const auto time = current_;
    current_ += step_;
    return time;
}

}  // namespace stage1
