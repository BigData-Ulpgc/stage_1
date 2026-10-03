#include <gtest/gtest.h>

#include "stage1/benchmark/datalake_write_benchmark.hpp"
#include "stage1/datalake/range_based_datalake.hpp"
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

TEST(BenchmarkDatalakeWrite, GivesFiveElapsedThenFiveThroughputRowsPerStructureInJavasOrder) {
    TempDir root("stage1_datalake_write_benchmark_test");

    const auto results = benchmark_datalake_write("cpp", kCorpus, root.path());

    ASSERT_EQ(results.size(), 30u);  // 3 structures x (5 elapsed + 5 throughput)
    const std::vector<std::string> structures = {"book", "range", "time"};
    for (std::size_t s = 0; s < structures.size(); ++s) {
        for (int i = 0; i < 5; ++i) {
            const auto& elapsed = results[s * 10 + i];
            const auto& throughput = results[s * 10 + 5 + i];
            EXPECT_EQ(elapsed.experiment, "datalake_write");
            EXPECT_EQ(elapsed.structure, structures[s]);
            EXPECT_EQ(elapsed.dataset_size, 2);
            EXPECT_EQ(elapsed.metric, "elapsed");
            EXPECT_EQ(elapsed.repetition, i + 1);
            EXPECT_EQ(throughput.structure, structures[s]);
            EXPECT_EQ(throughput.metric, "throughput");
            EXPECT_EQ(throughput.unit, "books_per_s");
            EXPECT_EQ(throughput.repetition, i + 1);
            EXPECT_DOUBLE_EQ(throughput.value, 2 / (std::max(elapsed.value, 0.001) / 1000.0));
        }
    }
}

TEST(BenchmarkDatalakeWrite, WritesTheBooksUnderEachLayoutWithTheSimulatedClockForTime) {
    TempDir root("stage1_datalake_write_benchmark_test_files");

    benchmark_datalake_write("cpp", kCorpus, root.path());

    EXPECT_TRUE(std::filesystem::exists(root.path() / "book" / "1342" / "body.txt"));
    EXPECT_TRUE(std::filesystem::exists(root.path() / "range" / stage1::range_folder_name(1342) / "1342.body.txt"));
    // Not today's folder: the first simulated hour, the same in every repetition.
    EXPECT_TRUE(std::filesystem::exists(root.path() / "time" / "20260101" / "00" / "1342.body.txt"));
}
