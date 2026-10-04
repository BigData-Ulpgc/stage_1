#include <gtest/gtest.h>

#include <map>
#include <string>

#include "stage1/benchmark/index/index_memory_benchmark.hpp"
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
    EXPECT_GT(value.at("monolithic/heap_after_open"), 0.0);
    // How monolithic and hierarchical compare after opening is checked on a
    // larger index below: with 2 tiny books both are a few hundred bytes, and
    // which one is bigger depends on the allocator (glibc: 256 vs 224).
}

TEST(BenchmarkIndexMemory, OpeningMonolithicHoldsTheWholeIndexWhileHierarchicalHoldsAlmostNothing) {
    TempDir root("stage1_index_memory_benchmark_test_large");
    // Two books of 3,000 distinct terms each, 1,500 of them shared: 4,500 terms.
    std::string first_body;
    std::string second_body;
    for (int i = 0; i < 3000; ++i) {
        first_body += "w" + std::to_string(i) + " ";
        second_body += "w" + std::to_string(i + 1500) + " ";
    }
    const std::vector<SampleBook> books = {{1, first_body, ""}, {2, second_body, ""}};

    const auto results = benchmark_index_memory("cpp", books, {"w1 w2000", "w4000"}, {}, root.path());

    std::map<std::string, double> value;
    for (const auto& result : results) {
        value[result.structure + "/" + result.metric] = result.value;
    }
    // Monolithic parses its whole JSON up front; hierarchical keeps only its
    // folder's path and reads one file per lookup. At this size the gap is
    // orders of magnitude, whatever the allocator.
    EXPECT_GT(value.at("monolithic/heap_after_open"), 100000.0);
    EXPECT_LT(value.at("hierarchical/heap_after_open") * 10, value.at("monolithic/heap_after_open"));
}
