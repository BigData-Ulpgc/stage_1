#include <gtest/gtest.h>

#include <map>

#include "stage1/benchmark/index_memory_benchmark.hpp"
#include "stage1/datamart/index/mongo_index_writer.hpp"
#include "support/temp_dir.hpp"

using stage1::benchmark_index_memory;
using stage1::SampleBook;
using stage1::testing::TempDir;

namespace {

const std::vector<SampleBook> kCorpus = {
    {1342, "the whale swims near the island.", ""},
    {84, "the boat sails to the island.", ""},
};
const std::unordered_set<std::string> kStopwords = {"the", "to", "near"};
const std::vector<std::string> kQueries = {"whale island", "boat"};

}  // namespace

TEST(BenchmarkIndexMemory, ReportsHeapAfterBuildAndAfterOpenPerStructureLikeTheJavaModule) {
    TempDir root("stage1_index_memory_benchmark_test");

    auto results = benchmark_index_memory("cpp", kCorpus, kQueries, kStopwords, root.path());

    std::map<std::string, double> value;  // "structure/metric" -> bytes
    for (const auto& result : results) {
        EXPECT_EQ(result.language, "cpp");
        EXPECT_EQ(result.experiment, "index_memory");
        EXPECT_EQ(result.dataset_size, 2);
        EXPECT_EQ(result.unit, "bytes");
        value[result.structure + "/" + result.metric] = result.value;
    }
    EXPECT_EQ(results.size(), stage1::mongo_is_reachable() ? 6u : 4u);
    // Building keeps the whole in-memory index alive, whatever the structure.
    EXPECT_GT(value.at("monolithic/heap_after_build"), 0.0);
    EXPECT_GT(value.at("hierarchical/heap_after_build"), 0.0);
    // Opening: monolithic parses its whole JSON, hierarchical keeps only a path.
    EXPECT_GT(value.at("monolithic/heap_after_open"), 0.0);
    EXPECT_LT(value.at("hierarchical/heap_after_open"), value.at("monolithic/heap_after_open"));
}
