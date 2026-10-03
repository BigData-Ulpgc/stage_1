#include <gtest/gtest.h>

#include "stage1/benchmark/datalake_incremental_benchmark.hpp"
#include "support/temp_dir.hpp"

using stage1::benchmark_datalake_incremental;
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

TEST(BenchmarkDatalakeIncremental, GivesFiveElapsedThenFiveDetectedRowsPerStructureInJavasOrder) {
    TempDir root("stage1_datalake_incremental_benchmark_test");

    // 20 books: the last 10% (books 19 and 20) are the fresh ones.
    const auto results = benchmark_datalake_incremental("cpp", make_corpus(20), root.path());

    ASSERT_EQ(results.size(), 30u);  // 3 structures x (5 elapsed + 5 detected)
    const std::vector<std::string> structures = {"book", "range", "time"};
    for (std::size_t s = 0; s < structures.size(); ++s) {
        for (int i = 0; i < 5; ++i) {
            const auto& elapsed = results[s * 10 + i];
            const auto& detected = results[s * 10 + 5 + i];
            EXPECT_EQ(elapsed.experiment, "datalake_incremental");
            EXPECT_EQ(elapsed.structure, structures[s]);
            EXPECT_EQ(elapsed.dataset_size, 20);
            EXPECT_EQ(elapsed.metric, "elapsed");
            EXPECT_EQ(elapsed.repetition, i + 1);
            EXPECT_EQ(detected.structure, structures[s]);
            EXPECT_EQ(detected.metric, "detected");
            EXPECT_EQ(detected.unit, "books");
            EXPECT_EQ(detected.repetition, i + 1);
            EXPECT_EQ(detected.value, 2.0);
        }
    }
}

TEST(BenchmarkDatalakeIncremental, ThrowsWithFewerThanTwoBooks) {
    TempDir root("stage1_datalake_incremental_benchmark_test_small");
    EXPECT_THROW(benchmark_datalake_incremental("cpp", make_corpus(1), root.path()), std::invalid_argument);
}
