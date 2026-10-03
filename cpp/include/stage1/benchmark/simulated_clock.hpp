#pragma once

#include <chrono>

#include "stage1/datalake/time_based_datalake.hpp"

namespace stage1 {

// A Clock that moves a fixed step forward every time it is asked for the time
// (the Java module's benchmark.SimulatedClock). With the real clock, the 200
// books of a datalake benchmark are written in well under a second and all
// land in one YYYYMMDD/HH folder. With this one, every write happens "a while
// later", as if the ingestion lasted hours, and always in the same way, so
// every repetition builds the same tree.
class SimulatedClock : public Clock {
public:
    SimulatedClock(std::chrono::system_clock::time_point start, std::chrono::system_clock::duration step)
        : current_(start), step_(step) {}

    // Java's SimulatedClock.tenBooksPerHour(): starts on 1 January 2026 at
    // 00:00 and moves 6 minutes per call, so each hour folder gets 10 books.
    // The start is local midnight, because time_folder_name uses local time
    // (SPEC section 3): the folders are 20260101/00, 20260101/01, ... on any
    // machine, the same names Java gets with its UTC clock.
    static SimulatedClock ten_books_per_hour();

    // Returns the current simulated time, then moves it one step forward.
    std::chrono::system_clock::time_point now() const override;

private:
    // now() is const in the Clock contract, but reading this clock moves it:
    // `mutable` lets a const member function change this one member.
    mutable std::chrono::system_clock::time_point current_;
    std::chrono::system_clock::duration step_;
};

}  // namespace stage1
