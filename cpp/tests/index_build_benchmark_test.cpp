#include <gtest/gtest.h>

#include <algorithm>
#include <fstream>
#include <nlohmann/json.hpp>

#include "stage1/index_build_benchmark.hpp"
#include "stage1/mongo_index_writer.hpp"
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

}  // namespace

TEST(BenchmarkIndexBuild, ProducesFiveMeasuredRowsForMonolithicAndHierarchical) {
    TempDir root("stage1_index_build_benchmark_test");

    auto results = benchmark_index_build("cpp", kCorpus, kStopwords, root.path());

    int monolithic_rows = 0;
    int hierarchical_rows = 0;
    for (const auto& result : results) {
        EXPECT_EQ(result.language, "cpp");
        EXPECT_EQ(result.experiment, "index_build");
        EXPECT_EQ(result.dataset_size, 2);
        EXPECT_EQ(result.metric, "elapsed");
        EXPECT_EQ(result.unit, "ms");
        EXPECT_GE(result.value, 0.0);
        if (result.structure == "monolithic") {
            EXPECT_EQ(result.repetition, ++monolithic_rows);
        } else if (result.structure == "hierarchical") {
            EXPECT_EQ(result.repetition, ++hierarchical_rows);
        }
    }
    EXPECT_EQ(monolithic_rows, 5);
    EXPECT_EQ(hierarchical_rows, 5);
}

TEST(BenchmarkIndexBuild, IncludesMongoOnlyWhenReachable) {
    TempDir root("stage1_index_build_benchmark_test_mongo");

    auto results = benchmark_index_build("cpp", kCorpus, kStopwords, root.path());

    const int mongo_rows =
        static_cast<int>(std::count_if(results.begin(), results.end(), [](const auto& r) { return r.structure == "mongo"; }));
    if (stage1::mongo_is_reachable()) {
        EXPECT_EQ(mongo_rows, 5);
    } else {
        EXPECT_EQ(mongo_rows, 0);
    }
}

TEST(BenchmarkIndexBuild, WritesAWorkingMonolithicIndex) {
    TempDir root("stage1_index_build_benchmark_test_content");

    benchmark_index_build("cpp", kCorpus, kStopwords, root.path());

    std::ifstream file(root.path() / "monolithic" / "inverted_index.json");
    const auto document = nlohmann::json::parse(file);
    EXPECT_EQ(document["whale"], nlohmann::json({1}));
    EXPECT_EQ(document["boat"], nlohmann::json({2}));
}
