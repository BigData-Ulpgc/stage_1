#include <gtest/gtest.h>

#include "stage1/benchmark/metadata_insert_benchmark.hpp"
#include "support/temp_dir.hpp"

using stage1::benchmark_metadata_insert;
using stage1::SampleBook;
using stage1::testing::TempDir;

namespace {

const std::vector<SampleBook> kCorpus = {
    {1342, "body 1342", "Title: Pride and Prejudice\nAuthor: Jane Austen"},
    {84, "body 84", "Title: Frankenstein\nAuthor: Mary Shelley"},
};

}  // namespace

TEST(BenchmarkMetadataInsert, ProducesFiveElapsedAndFiveThroughputRows) {
    TempDir root("stage1_metadata_insert_benchmark_test");

    auto results = benchmark_metadata_insert("cpp", kCorpus, root.path());

    int elapsed_rows = 0, throughput_rows = 0;
    for (const auto& result : results) {
        EXPECT_EQ(result.language, "cpp");
        EXPECT_EQ(result.experiment, "metadata_insert");
        EXPECT_EQ(result.structure, "sqlite");
        EXPECT_EQ(result.dataset_size, 2);
        if (result.metric == "elapsed") {
            ++elapsed_rows;
            EXPECT_EQ(result.unit, "ms");
            EXPECT_GE(result.value, 0.0);
        }
        if (result.metric == "throughput") {
            ++throughput_rows;
            EXPECT_EQ(result.unit, "rows_per_s");
            EXPECT_GT(result.value, 0.0);
        }
    }
    EXPECT_EQ(elapsed_rows, 5);
    EXPECT_EQ(throughput_rows, 5);
}
