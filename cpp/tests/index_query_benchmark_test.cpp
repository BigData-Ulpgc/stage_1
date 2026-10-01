#include <gtest/gtest.h>

#include <algorithm>

#include "stage1/index_build_benchmark.hpp"
#include "stage1/index_query_benchmark.hpp"
#include "stage1/mongo_index_writer.hpp"
#include "support/temp_dir.hpp"

using stage1::benchmark_index_build;
using stage1::benchmark_index_query;
using stage1::SampleBook;
using stage1::testing::TempDir;

namespace {

const std::vector<SampleBook> kCorpus = {
    {1, "the whale swims near the island.", ""},
    {2, "the boat sails to the island.", ""},
};
const std::unordered_set<std::string> kStopwords = {"the", "to", "near"};
const std::vector<std::string> kQueries = {"whale island", "boat", "dragon"};

}  // namespace

TEST(BenchmarkIndexQuery, ProducesFiveMeasuredRowsPerAvailableStructure) {
    TempDir root("stage1_index_query_benchmark_test");
    benchmark_index_build("cpp", kCorpus, kStopwords, root.path());  // builds the structures to query

    auto results = benchmark_index_query("cpp", 2, kQueries, kStopwords, root.path());

    int monolithic_rows = 0;
    int hierarchical_rows = 0;
    for (const auto& result : results) {
        EXPECT_EQ(result.language, "cpp");
        EXPECT_EQ(result.experiment, "index_query");
        EXPECT_EQ(result.dataset_size, 2);
        EXPECT_EQ(result.unit, "ms");
        EXPECT_GE(result.value, 0.0);
        if (result.structure == "monolithic") ++monolithic_rows;
        if (result.structure == "hierarchical") ++hierarchical_rows;
    }
    EXPECT_EQ(monolithic_rows, 5);
    EXPECT_EQ(hierarchical_rows, 5);
}

TEST(BenchmarkIndexQuery, IncludesMongoOnlyWhenReachable) {
    TempDir root("stage1_index_query_benchmark_test_mongo");
    benchmark_index_build("cpp", kCorpus, kStopwords, root.path());

    auto results = benchmark_index_query("cpp", 2, kQueries, kStopwords, root.path());

    const int mongo_rows =
        static_cast<int>(std::count_if(results.begin(), results.end(), [](const auto& r) { return r.structure == "mongo"; }));
    if (stage1::mongo_is_reachable()) {
        EXPECT_EQ(mongo_rows, 5);
    } else {
        EXPECT_EQ(mongo_rows, 0);
    }
}
