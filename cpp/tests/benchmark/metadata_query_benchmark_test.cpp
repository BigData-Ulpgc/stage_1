#include <gtest/gtest.h>

#include "stage1/benchmark/metadata_query_benchmark.hpp"
#include "support/temp_dir.hpp"

using stage1::benchmark_metadata_query;
using stage1::SampleBook;
using stage1::testing::TempDir;

namespace {

const std::vector<SampleBook> kCorpus = {
    {1342, "body", "Title: Pride and Prejudice\nAuthor: Jane Austen"},
    {84, "body", "Title: Frankenstein\nAuthor: Mary Shelley"},
    {11, "body", "Title: Alice in Wonderland\nAuthor: Lewis Carroll"},
};

}  // namespace

TEST(BenchmarkMetadataQuery, ProducesFiveRowsForEachOfTheThreeQueryTypesPlusTheirAverages) {
    TempDir root("stage1_metadata_query_benchmark_test");

    auto results = benchmark_metadata_query("cpp", kCorpus, root.path(), /*query_count=*/20);

    int by_id = 0, by_id_avg = 0, by_author = 0, by_author_avg = 0, by_title = 0, by_title_avg = 0;
    for (const auto& result : results) {
        EXPECT_EQ(result.language, "cpp");
        EXPECT_EQ(result.experiment, "metadata_query");
        EXPECT_EQ(result.structure, "sqlite");
        EXPECT_EQ(result.dataset_size, 3);
        EXPECT_GE(result.value, 0.0);
        if (result.metric == "find_by_id") ++by_id;
        if (result.metric == "find_by_id_avg") ++by_id_avg;
        if (result.metric == "find_by_author") ++by_author;
        if (result.metric == "find_by_author_avg") ++by_author_avg;
        if (result.metric == "find_by_title") ++by_title;
        if (result.metric == "find_by_title_avg") ++by_title_avg;
    }
    EXPECT_EQ(by_id, 5);
    EXPECT_EQ(by_id_avg, 5);
    EXPECT_EQ(by_author, 5);
    EXPECT_EQ(by_author_avg, 5);
    EXPECT_EQ(by_title, 5);
    EXPECT_EQ(by_title_avg, 5);
}

TEST(BenchmarkMetadataQuery, ThrowsWhenABookHasNoAuthor) {
    TempDir root("stage1_metadata_query_benchmark_test_missing");
    const std::vector<SampleBook> missing_author = {{1, "body", "Title: Only A Title"}};

    EXPECT_THROW(benchmark_metadata_query("cpp", missing_author, root.path(), 10), std::invalid_argument);
}
