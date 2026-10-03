#include <gtest/gtest.h>

#include <stdexcept>

#include "stage1/benchmark/datalake/datalake_benchmark_support.hpp"
#include "stage1/util/file_io.hpp"
#include "support/temp_dir.hpp"

using stage1::fresh_datalake;
using stage1::testing::TempDir;

TEST(FreshDatalake, EmptiesTheFolderBeforeCreatingTheDatalake) {
    TempDir root("stage1_fresh_datalake_test_empty");
    const auto dir = root.path() / "book";
    stage1::write_text_file(dir / "7" / "body.txt", "left over");
    stage1::write_text_file(dir / "7" / "header.txt", "left over");

    const auto datalake = fresh_datalake("book", dir);

    EXPECT_TRUE(datalake->list_book_ids().empty());
    EXPECT_FALSE(std::filesystem::exists(dir / "7"));
}

TEST(FreshDatalake, EveryNewTimeDatalakeStartsAgainAtTheFirstSimulatedHour) {
    TempDir root("stage1_fresh_datalake_test_time");
    const auto dir = root.path() / "time";

    const auto first = fresh_datalake("time", dir)->write(1, "header", "body");
    const auto second = fresh_datalake("time", dir)->write(1, "header", "body");

    EXPECT_EQ(first.body_path, (dir / "20260101" / "00" / "1.body.txt").string());
    EXPECT_EQ(second.body_path, first.body_path);
}

TEST(FreshDatalake, RejectsAnUnknownStructure) {
    TempDir root("stage1_fresh_datalake_test_unknown");
    EXPECT_THROW(fresh_datalake("hash", root.path() / "hash"), std::invalid_argument);
}
