#include <gtest/gtest.h>

#include "stage1/index_update_benchmark.hpp"
#include "support/temp_dir.hpp"

using stage1::benchmark_index_update;
using stage1::SampleBook;
using stage1::testing::TempDir;

namespace {

std::vector<SampleBook> make_corpus(int count) {
    std::vector<SampleBook> books;
    for (int i = 1; i <= count; ++i) {
        books.push_back(SampleBook{i, "whale word" + std::to_string(i), ""});
    }
    return books;
}

const std::unordered_set<std::string> kStopwords = {"the"};

}  // namespace

TEST(BenchmarkIndexUpdate, ProducesFiveElapsedAndFivePerBookRowsForEachStructure) {
    TempDir root("stage1_index_update_benchmark_test");
    auto corpus = make_corpus(20);  // k = 2 added books

    auto results = benchmark_index_update("cpp", corpus, kStopwords, root.path());

    int monolithic_elapsed = 0, monolithic_per_book = 0, hierarchical_elapsed = 0, hierarchical_per_book = 0;
    for (const auto& result : results) {
        EXPECT_EQ(result.language, "cpp");
        EXPECT_EQ(result.experiment, "index_update");
        EXPECT_EQ(result.dataset_size, 20);
        EXPECT_EQ(result.unit, "ms");
        EXPECT_GE(result.value, 0.0);
        if (result.structure == "monolithic" && result.metric == "elapsed") ++monolithic_elapsed;
        if (result.structure == "monolithic" && result.metric == "per_book") ++monolithic_per_book;
        if (result.structure == "hierarchical" && result.metric == "elapsed") ++hierarchical_elapsed;
        if (result.structure == "hierarchical" && result.metric == "per_book") ++hierarchical_per_book;
    }
    EXPECT_EQ(monolithic_elapsed, 5);
    EXPECT_EQ(monolithic_per_book, 5);
    EXPECT_EQ(hierarchical_elapsed, 5);
    EXPECT_EQ(hierarchical_per_book, 5);
}

TEST(BenchmarkIndexUpdate, ThrowsWithFewerThanTwoBooks) {
    TempDir root("stage1_index_update_benchmark_test_small");
    EXPECT_THROW(benchmark_index_update("cpp", {make_corpus(1)[0]}, kStopwords, root.path()), std::invalid_argument);
}
