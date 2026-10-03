#include <gtest/gtest.h>

#include <algorithm>

#include "stage1/benchmark/metadata/metadata_insert_benchmark.hpp"
#include "support/temp_dir.hpp"

using stage1::benchmark_metadata_insert;
using stage1::synthetic_metadata;
using stage1::testing::TempDir;

TEST(BenchmarkMetadataInsert, GivesFiveElapsedThenFiveThroughputRowsPerVariantInJavasOrder) {
    TempDir root("stage1_metadata_insert_benchmark_test");

    // 25 rows in batches of 10: 3 batches, the last one partial. The function
    // itself throws if any row is missing afterwards.
    const auto results = benchmark_metadata_insert("cpp", synthetic_metadata(25), root.path(), 10);

    ASSERT_EQ(results.size(), 20u);  // 2 variants x (5 elapsed + 5 throughput)
    const std::vector<std::string> structures = {"sqlite", "sqlite_no_index"};
    for (std::size_t s = 0; s < structures.size(); ++s) {
        for (int i = 0; i < 5; ++i) {
            const auto& elapsed = results[s * 10 + i];
            const auto& throughput = results[s * 10 + 5 + i];
            EXPECT_EQ(elapsed.experiment, "metadata_insert");
            EXPECT_EQ(elapsed.structure, structures[s]);
            EXPECT_EQ(elapsed.dataset_size, 25);
            EXPECT_EQ(elapsed.metric, "elapsed");
            EXPECT_EQ(elapsed.repetition, i + 1);
            EXPECT_EQ(throughput.structure, structures[s]);
            EXPECT_EQ(throughput.metric, "throughput");
            EXPECT_EQ(throughput.unit, "rows_per_s");
            EXPECT_EQ(throughput.repetition, i + 1);
            EXPECT_DOUBLE_EQ(throughput.value, 25 / (std::max(elapsed.value, 0.001) / 1000.0));
        }
    }
}

TEST(BenchmarkMetadataInsert, NamesEachDatabaseAfterItsVariantAndSize) {
    TempDir root("stage1_metadata_insert_benchmark_test_files");

    benchmark_metadata_insert("cpp", synthetic_metadata(5), root.path());

    EXPECT_TRUE(std::filesystem::exists(root.path() / "insert" / "sqlite_5.db"));
    EXPECT_TRUE(std::filesystem::exists(root.path() / "insert" / "sqlite_no_index_5.db"));
}

TEST(BenchmarkMetadataInsert, RejectsABatchSizeOfZero) {
    TempDir root("stage1_metadata_insert_benchmark_test_zero");
    EXPECT_THROW(benchmark_metadata_insert("cpp", synthetic_metadata(5), root.path(), 0), std::invalid_argument);
}
