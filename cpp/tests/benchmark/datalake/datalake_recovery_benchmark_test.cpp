#include <gtest/gtest.h>

#include "stage1/benchmark/datalake/datalake_recovery_benchmark.hpp"
#include "support/temp_dir.hpp"

using stage1::benchmark_datalake_recovery;
using stage1::SampleBook;
using stage1::testing::TempDir;

namespace {

std::vector<SampleBook> make_corpus(int count) {
    std::vector<SampleBook> books;
    for (int i = 1; i <= count; ++i) {
        books.push_back(SampleBook{i, "body " + std::to_string(i), "header " + std::to_string(i)});
    }
    return books;
}

}  // namespace

TEST(BenchmarkDatalakeRecovery, RecoversEveryDamagedBookWithRowsInJavasOrder) {
    TempDir root("stage1_datalake_recovery_benchmark_test");

    // Positions 0 and 10 are damaged: books 1 and 11. Must not throw: the
    // function itself checks that nothing was lost or duplicated.
    const auto results = benchmark_datalake_recovery("cpp", make_corpus(20), root.path());

    ASSERT_EQ(results.size(), 24u);  // 3 structures x (5 elapsed + recovered + lost + duplicates)
    const std::vector<std::string> structures = {"book", "range", "time"};
    for (std::size_t s = 0; s < structures.size(); ++s) {
        for (int i = 0; i < 5; ++i) {
            EXPECT_EQ(results[s * 8 + i].structure, structures[s]);
            EXPECT_EQ(results[s * 8 + i].metric, "elapsed");
            EXPECT_EQ(results[s * 8 + i].repetition, i + 1);
        }
        EXPECT_EQ(results[s * 8 + 5].metric, "recovered");
        EXPECT_EQ(results[s * 8 + 5].value, 2.0);
        EXPECT_EQ(results[s * 8 + 6].metric, "lost");
        EXPECT_EQ(results[s * 8 + 6].value, 0.0);
        EXPECT_EQ(results[s * 8 + 7].metric, "duplicates");
        EXPECT_EQ(results[s * 8 + 7].value, 0.0);
        EXPECT_EQ(results[s * 8 + 7].unit, "books");
        EXPECT_EQ(results[s * 8 + 7].repetition, 1);
    }
}

TEST(BenchmarkDatalakeRecovery, DamagesTheFirstBookEvenWithFewerThanTenBooks) {
    TempDir root("stage1_datalake_recovery_benchmark_test_small");

    const auto results = benchmark_datalake_recovery("cpp", make_corpus(9), root.path());

    EXPECT_EQ(results[5].metric, "recovered");
    EXPECT_EQ(results[5].value, 1.0);  // position 0 only, as in Java
}
