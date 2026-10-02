#include <gtest/gtest.h>

#include <chrono>
#include <ctime>
#include <algorithm>
#include <fstream>
#include <sstream>

#include "fakes/fake_clock.hpp"
#include "stage1/datalake/time_based_datalake.hpp"
#include "support/temp_dir.hpp"

using stage1::SystemClock;
using stage1::time_folder_name;
using stage1::TimeBasedDatalake;
using stage1::testing::FakeClock;
using stage1::testing::TempDir;

namespace {

std::string read_file(const std::filesystem::path& path) {
    std::ifstream file(path, std::ios::binary);
    std::ostringstream contents;
    contents << file.rdbuf();
    return contents.str();
}

// Builds the time_point for a given LOCAL date and hour (minutes/seconds = 0).
// std::mktime interprets the fields it is given as local time, which is exactly
// the inverse of what time_folder_name does with localtime_r/localtime_s; using
// the same machine's own local-time rules on both ends keeps the round trip
// correct regardless of which timezone the test happens to run in.
std::chrono::system_clock::time_point make_local_time(int year, int month, int day, int hour) {
    std::tm tm{};
    tm.tm_year = year - 1900;
    tm.tm_mon = month - 1;
    tm.tm_mday = day;
    tm.tm_hour = hour;
    tm.tm_isdst = -1;  // let the C library decide whether daylight saving applies
    return std::chrono::system_clock::from_time_t(std::mktime(&tm));
}

}  // namespace

TEST(TimeFolderName, FormatsDateAndHourWithZeroPadding) {
    EXPECT_EQ(time_folder_name(make_local_time(2026, 9, 5, 8)), "20260905/08");
}

TEST(TimeFolderName, HandlesTheLastHourOfTheDay) {
    EXPECT_EQ(time_folder_name(make_local_time(2026, 12, 31, 23)), "20261231/23");
}

TEST(SystemClockTest, ReturnsSomethingCloseToTheRealNow) {
    SystemClock clock;
    const auto before = std::chrono::system_clock::now();
    const auto reported = clock.now();
    const auto after = std::chrono::system_clock::now();

    EXPECT_LE(before, reported);
    EXPECT_LE(reported, after);
}

TEST(TimeBasedDatalake, WritesBodyAndHeaderUnderTheClockedHour) {
    TempDir root("stage1_time_based_datalake_test");
    FakeClock clock(make_local_time(2026, 9, 5, 8));
    TimeBasedDatalake datalake(root.path(), clock);

    auto location = datalake.write(1342, "Title: Pride and Prejudice", "It is a truth universally acknowledged...");

    EXPECT_EQ(location.body_path, (root.path() / "20260905" / "08" / "1342.body.txt").string());
    EXPECT_EQ(location.header_path, (root.path() / "20260905" / "08" / "1342.header.txt").string());
    EXPECT_EQ(read_file(location.body_path), "It is a truth universally acknowledged...");
}

TEST(TimeBasedDatalake, BooksWrittenInDifferentHoursGetSeparateFolders) {
    TempDir root("stage1_time_based_datalake_test_hours");
    FakeClock morning_clock(make_local_time(2026, 9, 5, 8));
    FakeClock evening_clock(make_local_time(2026, 9, 5, 20));

    TimeBasedDatalake(root.path(), morning_clock).write(1, "h1", "morning body");
    TimeBasedDatalake(root.path(), evening_clock).write(2, "h2", "evening body");

    EXPECT_TRUE(std::filesystem::exists(root.path() / "20260905" / "08" / "1.body.txt"));
    EXPECT_TRUE(std::filesystem::exists(root.path() / "20260905" / "20" / "2.body.txt"));
}

TEST(TimeBasedDatalake, LocateFindsABookWrittenByTheSameInstance) {
    TempDir root("stage1_time_based_datalake_test_locate");
    FakeClock clock(make_local_time(2026, 9, 5, 8));
    TimeBasedDatalake datalake(root.path(), clock);
    datalake.write(1342, "header", "body");

    auto found = datalake.locate(1342);

    ASSERT_TRUE(found.has_value());
    EXPECT_EQ(found->body_path, (root.path() / "20260905" / "08" / "1342.body.txt").string());
}

TEST(TimeBasedDatalake, LocateReturnsNulloptFromAFreshInstanceEvenIfTheFileExists) {
    TempDir root("stage1_time_based_datalake_test_locate_fresh");
    FakeClock clock(make_local_time(2026, 9, 5, 8));
    TimeBasedDatalake(root.path(), clock).write(1342, "header", "body");

    // A new instance remembers nothing, even though the file is really
    // there: this is the real cost SPEC section 3 wants measured -- unlike
    // book/range, a fresh TimeBasedDatalake cannot compute where id 1342
    // landed without being told when it was written.
    TimeBasedDatalake reopened(root.path(), clock);
    EXPECT_FALSE(reopened.locate(1342).has_value());
}

TEST(TimeBasedDatalake, ListBookIdsFindsEveryWrittenBookEvenFromAFreshInstance) {
    TempDir root("stage1_time_based_datalake_test_list");
    FakeClock morning_clock(make_local_time(2026, 9, 5, 8));
    FakeClock evening_clock(make_local_time(2026, 9, 5, 20));
    TimeBasedDatalake(root.path(), morning_clock).write(1, "h", "b");
    TimeBasedDatalake(root.path(), evening_clock).write(2, "h", "b");

    // Unlike locate(), a brand new instance must still find both -- there is
    // nothing to remember, it is a real directory walk.
    TimeBasedDatalake reopened(root.path(), morning_clock);
    auto ids = reopened.list_book_ids();
    std::sort(ids.begin(), ids.end());

    EXPECT_EQ(ids, (std::vector<int>{1, 2}));
}
