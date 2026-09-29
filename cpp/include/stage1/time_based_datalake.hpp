#pragma once

#include <chrono>
#include <filesystem>
#include <string>

#include "stage1/datalake.hpp"

namespace stage1 {

// Abstract "what time is it" contract. TimeBasedDatalake asks a Clock instead of
// calling std::chrono::system_clock::now() directly, so its output is testable
// with a fixed, fake time instead of depending on whatever moment a test runs.
class Clock {
public:
    virtual ~Clock() = default;
    virtual std::chrono::system_clock::time_point now() const = 0;
};

// Clock backed by the real system clock; what production code uses.
class SystemClock : public Clock {
public:
    std::chrono::system_clock::time_point now() const override { return std::chrono::system_clock::now(); }
};

// "YYYYMMDD/HH" folder name for `time`, in local time and 24h format (shared/SPEC.md
// section 3). Pure with respect to I/O and to the wall clock: depends only on its
// argument, so it is directly testable without a fake Clock.
std::string time_folder_name(std::chrono::system_clock::time_point time);

// Time-based datalake layout (shared/SPEC.md section 3):
//   <root>/YYYYMMDD/HH/<ID>.body.txt
//   <root>/YYYYMMDD/HH/<ID>.header.txt
// The folder is named after the moment `write` is called, read from the injected
// Clock: a real SystemClock in production, a fixed FakeClock in tests.
class TimeBasedDatalake : public Datalake {
public:
    TimeBasedDatalake(std::filesystem::path root, Clock& clock) : root_(std::move(root)), clock_(clock) {}

    BookLocation write(int book_id, const std::string& header, const std::string& body) override;

private:
    std::filesystem::path root_;
    Clock& clock_;
};

}  // namespace stage1
