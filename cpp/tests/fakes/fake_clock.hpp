#pragma once

#include <chrono>

#include "stage1/time_based_datalake.hpp"

namespace stage1::testing {

// Test double for Clock: always reports the fixed time it was constructed
// with, instead of the real wall-clock time.
class FakeClock : public Clock {
public:
    explicit FakeClock(std::chrono::system_clock::time_point fixed_time) : fixed_time_(fixed_time) {}

    std::chrono::system_clock::time_point now() const override { return fixed_time_; }

private:
    std::chrono::system_clock::time_point fixed_time_;
};

}  // namespace stage1::testing
