#include <gtest/gtest.h>

#include "stage1/benchmark/metadata/metadata_query_benchmark.hpp"
#include "support/temp_dir.hpp"

using stage1::benchmark_metadata_query;
using stage1::synthetic_metadata;
using stage1::testing::TempDir;

TEST(BenchmarkMetadataQuery, GivesEachTypeAndItsAveragePerRepetitionInJavasOrder) {
    TempDir root("stage1_metadata_query_benchmark_test");

    // Must not throw: every query of the workload has to find something.
    const auto results = benchmark_metadata_query("cpp", synthetic_metadata(30), root.path(), 50);

    ASSERT_EQ(results.size(), 60u);  // 2 variants x 3 types x 5 repetitions x (total + avg)
    const std::vector<std::string> structures = {"sqlite", "sqlite_no_index"};
    const std::vector<std::string> types = {"find_by_id", "find_by_author", "find_by_title"};
    std::size_t row = 0;
    for (const auto& structure : structures) {
        for (const auto& type : types) {
            for (int repetition = 1; repetition <= 5; ++repetition) {
                const auto& total = results[row++];
                const auto& average = results[row++];
                EXPECT_EQ(total.experiment, "metadata_query");
                EXPECT_EQ(total.structure, structure);
                EXPECT_EQ(total.dataset_size, 30);
                EXPECT_EQ(total.metric, type);
                EXPECT_EQ(total.unit, "ms");
                EXPECT_EQ(total.repetition, repetition);
                EXPECT_EQ(average.metric, type + "_avg");
                EXPECT_EQ(average.unit, "us");
                EXPECT_EQ(average.repetition, repetition);
                EXPECT_DOUBLE_EQ(average.value, total.value * 1000.0 / 50);
            }
        }
    }
}

TEST(BenchmarkMetadataQuery, ThrowsWhenARowHasNoAuthor) {
    TempDir root("stage1_metadata_query_benchmark_test_no_author");
    auto rows = synthetic_metadata(1);
    rows[0].author = std::nullopt;

    EXPECT_THROW(benchmark_metadata_query("cpp", rows, root.path(), 10), std::invalid_argument);
}
