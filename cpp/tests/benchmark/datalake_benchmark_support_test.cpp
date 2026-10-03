#include <gtest/gtest.h>

#include <stdexcept>

#include "stage1/benchmark/datalake_benchmark_support.hpp"
#include "stage1/util/file_io.hpp"
#include "support/temp_dir.hpp"

using stage1::derived_rows;
using stage1::elapsed_rows;
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

TEST(ElapsedRows, NumbersTheRepetitionsFromOne) {
    const auto rows = elapsed_rows("cpp", "datalake_write", "book", 200, {5.0, 7.5});

    ASSERT_EQ(rows.size(), 2u);
    EXPECT_EQ(rows[0].repetition, 1);
    EXPECT_EQ(rows[0].metric, "elapsed");
    EXPECT_EQ(rows[0].unit, "ms");
    EXPECT_EQ(rows[1].repetition, 2);
    EXPECT_EQ(rows[1].value, 7.5);
    EXPECT_EQ(rows[1].dataset_size, 200);
}

TEST(DerivedRows, KeepEachRepetitionAndNeverDivideByZero) {
    const auto elapsed = elapsed_rows("cpp", "datalake_write", "book", 10, {0.0, 2.0});

    const auto rows = derived_rows(elapsed, "throughput", "books_per_s", [](double ms) { return 10 / (ms / 1000.0); });

    ASSERT_EQ(rows.size(), 2u);
    EXPECT_EQ(rows[0].repetition, 1);
    EXPECT_EQ(rows[0].metric, "throughput");
    EXPECT_EQ(rows[0].unit, "books_per_s");
    EXPECT_DOUBLE_EQ(rows[0].value, 10 / (0.001 / 1000.0));  // 0 ms counts as 0.001 ms, as in Java
    EXPECT_EQ(rows[1].repetition, 2);
    EXPECT_DOUBLE_EQ(rows[1].value, 5000.0);
    EXPECT_EQ(rows[1].structure, "book");
}
