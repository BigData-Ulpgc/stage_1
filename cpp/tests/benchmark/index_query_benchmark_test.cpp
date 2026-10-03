#include <gtest/gtest.h>

#include <algorithm>

#include "stage1/benchmark/index_query_benchmark.hpp"
#include "stage1/datamart/index/mongo_index_writer.hpp"
#include "support/temp_dir.hpp"

using stage1::BenchmarkResult;
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
constexpr int kRounds = 3;  // small: the tests check the shape of the result, not the timing

std::vector<BenchmarkResult> rows_of(const std::vector<BenchmarkResult>& results, const std::string& structure,
                                     const std::string& metric) {
    std::vector<BenchmarkResult> rows;
    std::copy_if(results.begin(), results.end(), std::back_inserter(rows),
                 [&](const BenchmarkResult& r) { return r.structure == structure && r.metric == metric; });
    return rows;
}

}  // namespace

TEST(BenchmarkIndexQuery, ProducesElapsedAndPerQueryRowsLikeTheJavaModule) {
    TempDir root("stage1_index_query_benchmark_test");

    auto results = benchmark_index_query("cpp", kCorpus, kQueries, kStopwords, root.path(), kRounds);

    for (const std::string structure : {"monolithic", "hierarchical"}) {
        const auto elapsed = rows_of(results, structure, "elapsed");
        const auto per_query = rows_of(results, structure, "per_query");
        ASSERT_EQ(elapsed.size(), 5u) << structure;
        ASSERT_EQ(per_query.size(), 5u) << structure;
        for (std::size_t i = 0; i < elapsed.size(); ++i) {
            EXPECT_EQ(elapsed[i].unit, "ms");
            EXPECT_EQ(per_query[i].unit, "us");
            EXPECT_EQ(elapsed[i].dataset_size, 2);
            EXPECT_EQ(per_query[i].repetition, elapsed[i].repetition);
            // per_query (µs) = elapsed (ms) * 1000 / (rounds * queries)
            EXPECT_NEAR(per_query[i].value, elapsed[i].value * 1000.0 / (kRounds * kQueries.size()), 1e-9);
        }
    }
}

TEST(BenchmarkIndexQuery, IncludesMongoOnlyWhenReachable) {
    TempDir root("stage1_index_query_benchmark_test_mongo");

    auto results = benchmark_index_query("cpp", kCorpus, kQueries, kStopwords, root.path(), kRounds);

    const auto mongo_rows = rows_of(results, "mongo", "elapsed").size() + rows_of(results, "mongo", "per_query").size();
    EXPECT_EQ(mongo_rows, stage1::mongo_is_reachable() ? 10u : 0u);
}

TEST(BenchmarkIndexQuery, WritesTheStructuresItQueries) {
    TempDir root("stage1_index_query_benchmark_test_files");

    benchmark_index_query("cpp", kCorpus, kQueries, kStopwords, root.path(), kRounds);

    EXPECT_TRUE(std::filesystem::exists(root.path() / "monolithic" / "inverted_index.json"));
    EXPECT_TRUE(std::filesystem::exists(root.path() / "hierarchical" / "inverted_index" / "W" / "whale.txt"));
}
