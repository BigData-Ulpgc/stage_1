#include <gtest/gtest.h>

#include <algorithm>
#include <fstream>
#include <nlohmann/json.hpp>

#include "stage1/benchmark/index_build_benchmark.hpp"
#include "stage1/datamart/index/mongo_index_writer.hpp"
#include "support/temp_dir.hpp"

using stage1::benchmark_index_build;
using stage1::SampleBook;
using stage1::testing::TempDir;

namespace {

const std::vector<SampleBook> kCorpus = {
    {1, "the whale swims. the whale dives.", ""},
    {2, "the boat sails.", ""},
};
const std::unordered_set<std::string> kStopwords = {"the"};
const std::vector<std::string> kQueries = {"whale", "boat", "whale boat"};

}  // namespace

TEST(BenchmarkIndexBuild, ProducesElapsedThenThroughputRowsLikeTheJavaModule) {
    TempDir root("stage1_index_build_benchmark_test");

    auto results = benchmark_index_build("cpp", kCorpus, kQueries, kStopwords, root.path());

    for (const std::string structure : {"monolithic", "hierarchical"}) {
        std::vector<stage1::BenchmarkResult> elapsed, throughput;
        for (const auto& r : results) {
            if (r.structure != structure) continue;
            EXPECT_EQ(r.experiment, "index_build");
            EXPECT_EQ(r.dataset_size, 2);
            (r.metric == "elapsed" ? elapsed : throughput).push_back(r);
        }
        ASSERT_EQ(elapsed.size(), 5u) << structure;
        ASSERT_EQ(throughput.size(), 5u) << structure;
        for (std::size_t i = 0; i < 5; ++i) {
            EXPECT_EQ(elapsed[i].unit, "ms");
            EXPECT_EQ(elapsed[i].repetition, static_cast<int>(i) + 1);
            EXPECT_EQ(throughput[i].metric, "throughput");
            EXPECT_EQ(throughput[i].unit, "books_per_s");
            EXPECT_EQ(throughput[i].repetition, elapsed[i].repetition);
            // books_per_s = N / elapsed in seconds (Java: elapsed floored at 0.001 ms)
            EXPECT_NEAR(throughput[i].value, 2 / (std::max(elapsed[i].value, 0.001) / 1000.0),
                        throughput[i].value * 1e-9);
        }
    }
}

TEST(BenchmarkIndexBuild, IncludesMongoOnlyWhenReachable) {
    TempDir root("stage1_index_build_benchmark_test_mongo");

    auto results = benchmark_index_build("cpp", kCorpus, kQueries, kStopwords, root.path());

    const int mongo_rows =
        static_cast<int>(std::count_if(results.begin(), results.end(), [](const auto& r) { return r.structure == "mongo"; }));
    if (stage1::mongo_is_reachable()) {
        EXPECT_EQ(mongo_rows, 10);
    } else {
        EXPECT_EQ(mongo_rows, 0);
    }
}

TEST(BenchmarkIndexBuild, WritesAWorkingMonolithicIndex) {
    TempDir root("stage1_index_build_benchmark_test_content");

    benchmark_index_build("cpp", kCorpus, kQueries, kStopwords, root.path());

    std::ifstream file(root.path() / "monolithic" / "inverted_index.json");
    const auto document = nlohmann::json::parse(file);
    EXPECT_EQ(document["whale"], nlohmann::json({1}));
    EXPECT_EQ(document["boat"], nlohmann::json({2}));
}
