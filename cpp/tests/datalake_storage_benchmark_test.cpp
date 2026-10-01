#include <gtest/gtest.h>

#include "stage1/datalake_storage_benchmark.hpp"
#include "support/temp_dir.hpp"

using stage1::benchmark_datalake_storage;
using stage1::SampleBook;
using stage1::testing::TempDir;

namespace {

const std::vector<SampleBook> kCorpus = {
    {1342, "It is a truth universally acknowledged.", "Title: Pride and Prejudice"},
    {84, "You will rejoice to hear.", "Title: Frankenstein"},
    {11, "Alice was beginning to get very tired.", "Title: Alice in Wonderland"},
};

double metric_value(const std::vector<stage1::BenchmarkResult>& results, const std::string& structure,
                     const std::string& metric) {
    for (const auto& result : results) {
        if (result.structure == structure && result.metric == metric) {
            return result.value;
        }
    }
    ADD_FAILURE() << "no " << metric << " row for structure " << structure;
    return -1;
}

}  // namespace

TEST(BenchmarkDatalakeStorage, BookLayoutHasTwoFilesAndOneDirectoryPerBook) {
    TempDir root("stage1_datalake_storage_benchmark_test_book");

    auto results = benchmark_datalake_storage("cpp", kCorpus, root.path());

    EXPECT_EQ(metric_value(results, "book", "files"), 6.0);        // 2 per book x 3 books
    EXPECT_EQ(metric_value(results, "book", "directories"), 3.0);  // 1 per book
    // Every book's own directory has 2 entries; root has 3 (one per book).
    EXPECT_EQ(metric_value(results, "book", "max_entries_per_dir"), 3.0);
}

TEST(BenchmarkDatalakeStorage, AllThreeStructuresReportTheSameTotalBytes) {
    TempDir root("stage1_datalake_storage_benchmark_test_bytes");

    auto results = benchmark_datalake_storage("cpp", kCorpus, root.path());

    const double expected_bytes = 0 + [] {
        double total = 0;
        for (const auto& book : kCorpus) total += static_cast<double>(book.body.size() + book.header.size());
        return total;
    }();

    EXPECT_EQ(metric_value(results, "book", "bytes"), expected_bytes);
    EXPECT_EQ(metric_value(results, "range", "bytes"), expected_bytes);
    EXPECT_EQ(metric_value(results, "time", "bytes"), expected_bytes);
}

TEST(BenchmarkDatalakeStorage, RangeLayoutGroupsAllThreeBooksInOneFolderHere) {
    TempDir root("stage1_datalake_storage_benchmark_test_range");

    // 1342, 84 and 11 all fall in the 0-999/1000-1999 split... 84 and 11 share
    // 00000-00999, 1342 is alone in 01000-01999: two range directories.
    auto results = benchmark_datalake_storage("cpp", kCorpus, root.path());

    EXPECT_EQ(metric_value(results, "range", "files"), 6.0);
    EXPECT_EQ(metric_value(results, "range", "directories"), 2.0);
}
