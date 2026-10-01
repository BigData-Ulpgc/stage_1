#include <gtest/gtest.h>

#include "stage1/datalake_write_benchmark.hpp"
#include "stage1/range_based_datalake.hpp"
#include "stage1/time_based_datalake.hpp"
#include "support/temp_dir.hpp"

using stage1::benchmark_datalake_write;
using stage1::SampleBook;
using stage1::testing::TempDir;

namespace {

const std::vector<SampleBook> kCorpus = {
    {1342, "It is a truth universally acknowledged.", "Title: Pride and Prejudice"},
    {84, "You will rejoice to hear.", "Title: Frankenstein"},
};

}  // namespace

TEST(BenchmarkDatalakeWrite, ProducesFiveMeasuredRowsForEachOfTheThreeStructures) {
    TempDir root("stage1_datalake_write_benchmark_test");

    auto results = benchmark_datalake_write("cpp", kCorpus, root.path());

    int book_rows = 0, range_rows = 0, time_rows = 0;
    for (const auto& result : results) {
        EXPECT_EQ(result.language, "cpp");
        EXPECT_EQ(result.experiment, "datalake_write");
        EXPECT_EQ(result.dataset_size, 2);
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

TEST(BenchmarkDatalakeWrite, ActuallyWritesTheBooksUnderEachLayout) {
    TempDir root("stage1_datalake_write_benchmark_test_files");

    benchmark_datalake_write("cpp", kCorpus, root.path());

    EXPECT_TRUE(std::filesystem::exists(root.path() / "book" / "1342" / "body.txt"));
    EXPECT_TRUE(std::filesystem::exists(root.path() / "range" / stage1::range_folder_name(1342) / "1342.body.txt"));

    // Written "now", so it must land in today's YYYYMMDD/HH folder.
    const auto today = stage1::time_folder_name(std::chrono::system_clock::now());
    EXPECT_TRUE(std::filesystem::exists(root.path() / "time" / today / "1342.body.txt"));
}
