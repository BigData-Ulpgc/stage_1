#include <gtest/gtest.h>

#include "stage1/benchmark/datalake_incremental_benchmark.hpp"
#include "support/temp_dir.hpp"

using stage1::benchmark_datalake_incremental;
using stage1::SampleBook;
using stage1::testing::TempDir;

namespace {

const std::vector<SampleBook> kCorpus = {
    {1, "body 1", "header 1"}, {2, "body 2", "header 2"}, {3, "body 3", "header 3"},
    {4, "body 4", "header 4"}, {5, "body 5", "header 5"}, {6, "body 6", "header 6"},
    {7, "body 7", "header 7"}, {8, "body 8", "header 8"}, {9, "body 9", "header 9"},
    {10, "body 10", "header 10"},
};

}  // namespace

TEST(BenchmarkDatalakeIncremental, ProducesFiveMeasuredRowsForEachOfTheThreeStructures) {
    TempDir root("stage1_datalake_incremental_benchmark_test");

    auto results = benchmark_datalake_incremental("cpp", kCorpus, root.path());

    int book_rows = 0, range_rows = 0, time_rows = 0;
    for (const auto& result : results) {
        EXPECT_EQ(result.language, "cpp");
        EXPECT_EQ(result.experiment, "datalake_incremental");
        EXPECT_EQ(result.dataset_size, 10);
        EXPECT_EQ(result.unit, "ms");
        EXPECT_GE(result.value, 0.0);
        if (result.structure == "book") ++book_rows;
        if (result.structure == "range") ++range_rows;
        if (result.structure == "time") ++time_rows;
    }
    EXPECT_EQ(book_rows, 5);
    EXPECT_EQ(range_rows, 5);
    EXPECT_EQ(time_rows, 5);
}

TEST(BenchmarkDatalakeIncremental, ThrowsWithFewerThanTwoBooks) {
    TempDir root("stage1_datalake_incremental_benchmark_test_small");
    EXPECT_THROW(benchmark_datalake_incremental("cpp", {kCorpus[0]}, root.path()), std::invalid_argument);
}
