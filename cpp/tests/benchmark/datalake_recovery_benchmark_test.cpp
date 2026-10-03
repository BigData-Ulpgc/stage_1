#include <gtest/gtest.h>

#include "stage1/benchmark/datalake_recovery_benchmark.hpp"
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

TEST(BenchmarkDatalakeRecovery, RecoversEveryDamagedBookWithNoLossOrDuplicates) {
    TempDir root("stage1_datalake_recovery_benchmark_test");
    auto corpus = make_corpus(20);  // every 10th -> books 10 and 20 get damaged

    // Must not throw: the function itself verifies no books were lost and no
    // duplicate body files were left behind after recovery.
    auto results = benchmark_datalake_recovery("cpp", corpus, root.path());

    int book_elapsed_rows = 0;
    bool saw_book_recovered = false;
    for (const auto& result : results) {
        EXPECT_EQ(result.language, "cpp");
        EXPECT_EQ(result.experiment, "datalake_recovery");
        EXPECT_EQ(result.dataset_size, 20);
        if (result.structure == "book" && result.metric == "elapsed") {
            ++book_elapsed_rows;
            EXPECT_GE(result.value, 0.0);
        }
        if (result.structure == "book" && result.metric == "recovered") {
            saw_book_recovered = true;
            EXPECT_EQ(result.value, 2.0);  // books 10 and 20
        }
        if (result.metric == "duplicates") {
            EXPECT_EQ(result.value, 0.0);
        }
    }
    EXPECT_EQ(book_elapsed_rows, 5);
    EXPECT_TRUE(saw_book_recovered);
}

TEST(BenchmarkDatalakeRecovery, ThrowsWithFewerThanTenBooks) {
    TempDir root("stage1_datalake_recovery_benchmark_test_small");
    EXPECT_THROW(benchmark_datalake_recovery("cpp", make_corpus(9), root.path()), std::invalid_argument);
}
