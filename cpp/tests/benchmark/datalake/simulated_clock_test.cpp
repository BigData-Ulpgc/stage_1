#include <gtest/gtest.h>

#include <chrono>

#include "stage1/benchmark/datalake/simulated_clock.hpp"

using stage1::SimulatedClock;
using stage1::time_folder_name;

TEST(SimulatedClock, ReturnsTheStartFirstAndThenMovesOneStepPerCall) {
    const auto start = std::chrono::system_clock::time_point{} + std::chrono::hours(1000);
    const SimulatedClock clock(start, std::chrono::minutes(6));

    EXPECT_EQ(clock.now(), start);
    EXPECT_EQ(clock.now(), start + std::chrono::minutes(6));
    EXPECT_EQ(clock.now(), start + std::chrono::minutes(12));
}

TEST(SimulatedClock, TenBooksPerHourPutsTenWritesInEachHourFolderFromJanuaryFirst) {
    const auto clock = SimulatedClock::ten_books_per_hour();

    for (int i = 0; i < 10; ++i) {
        EXPECT_EQ(time_folder_name(clock.now()), "20260101/00") << "write " << i;
    }
    EXPECT_EQ(time_folder_name(clock.now()), "20260101/01");
}
