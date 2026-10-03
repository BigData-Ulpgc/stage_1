#include <gtest/gtest.h>

#include "stage1/benchmark/datalake/datalake_lookup_benchmark.hpp"
#include "support/temp_dir.hpp"

using stage1::benchmark_datalake_lookup;
using stage1::SampleBook;
using stage1::testing::TempDir;

namespace {

const std::vector<SampleBook> kCorpus = {
    {1342, "It is a truth universally acknowledged.", "Title: Pride and Prejudice"},
    {84, "You will rejoice to hear.", "Title: Frankenstein"},
};

}  // namespace

TEST(BenchmarkDatalakeLookup, GivesFiveElapsedThenFivePerLookupRowsPerStructureInJavasOrder) {
    TempDir root("stage1_datalake_lookup_benchmark_test");

    const auto results = benchmark_datalake_lookup("cpp", kCorpus, root.path());

    ASSERT_EQ(results.size(), 30u);  // 3 structures x (5 elapsed + 5 per_lookup)
    const std::vector<std::string> structures = {"book", "range", "time"};
    for (std::size_t s = 0; s < structures.size(); ++s) {
        for (int i = 0; i < 5; ++i) {
            const auto& elapsed = results[s * 10 + i];
            const auto& per_lookup = results[s * 10 + 5 + i];
            EXPECT_EQ(elapsed.experiment, "datalake_lookup");
            EXPECT_EQ(elapsed.structure, structures[s]);
            EXPECT_EQ(elapsed.metric, "elapsed");
            EXPECT_EQ(elapsed.repetition, i + 1);
            EXPECT_EQ(per_lookup.structure, structures[s]);
            EXPECT_EQ(per_lookup.metric, "per_lookup");
            EXPECT_EQ(per_lookup.unit, "us");
            EXPECT_EQ(per_lookup.repetition, i + 1);
            EXPECT_DOUBLE_EQ(per_lookup.value, std::max(elapsed.value, 0.001) * 1000.0 / 2);
        }
    }
}
