#include <gtest/gtest.h>

#include <chrono>
#include <stdexcept>

#include "fakes/fake_clock.hpp"
#include "stage1/config/datalake_factory.hpp"
#include "stage1/datalake/range_based_datalake.hpp"
#include "support/temp_dir.hpp"

using stage1::create_datalake;
using stage1::testing::FakeClock;
using stage1::testing::TempDir;

TEST(CreateDatalake, BuildsEachLayoutOfSpecSection3) {
    TempDir root("stage1_datalake_factory_test_layouts");

    const auto book = create_datalake("book", root.path() / "book", nullptr)->write(1342, "h", "b");
    const auto range = create_datalake("range", root.path() / "range", nullptr)->write(1342, "h", "b");

    EXPECT_EQ(book.body_path, (root.path() / "book" / "1342" / "body.txt").string());
    EXPECT_EQ(range.body_path, (root.path() / "range" / stage1::range_folder_name(1342) / "1342.body.txt").string());
}

TEST(CreateDatalake, TheTimeLayoutUsesAndKeepsTheClockItIsGiven) {
    TempDir root("stage1_datalake_factory_test_time");
    const auto moment = std::chrono::system_clock::now();

    const auto datalake = create_datalake("time", root.path() / "time", std::make_unique<FakeClock>(moment));
    const auto written = datalake->write(84, "h", "b");  // the clock is used after create_datalake returned

    EXPECT_EQ(written.body_path, (root.path() / "time" / stage1::time_folder_name(moment) / "84.body.txt").string());
}

TEST(CreateDatalake, RejectsAnUnknownLayoutAndATimeLayoutWithoutClock) {
    TempDir root("stage1_datalake_factory_test_errors");

    EXPECT_THROW(create_datalake("hash", root.path(), nullptr), std::invalid_argument);
    EXPECT_THROW(create_datalake("time", root.path(), nullptr), std::invalid_argument);
}
