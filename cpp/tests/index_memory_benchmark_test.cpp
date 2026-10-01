#include <gtest/gtest.h>

#include "stage1/index_memory_benchmark.hpp"
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

}  // namespace

TEST(BenchmarkIndexMemory, ProducesOneRowForInMemoryIndexAndOneForMonolithic) {
    TempDir root("stage1_index_memory_benchmark_test");

    auto results = benchmark_index_memory("cpp", kCorpus, kStopwords, root.path());

    bool saw_in_memory_index = false;
    bool saw_monolithic = false;
    for (const auto& result : results) {
        EXPECT_EQ(result.language, "cpp");
        EXPECT_EQ(result.experiment, "index_memory");
        EXPECT_EQ(result.dataset_size, 2);
        EXPECT_EQ(result.metric, "rss_delta");
        EXPECT_EQ(result.unit, "bytes");
        EXPECT_GE(result.value, 0.0);
        if (result.structure == "in_memory_index") saw_in_memory_index = true;
        if (result.structure == "monolithic") saw_monolithic = true;
    }
    EXPECT_TRUE(saw_in_memory_index);
    EXPECT_TRUE(saw_monolithic);
    EXPECT_EQ(results.size(), 2u);
}
