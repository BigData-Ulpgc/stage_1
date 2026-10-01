#include <gtest/gtest.h>

#include "stage1/index_disk_benchmark.hpp"
#include "support/temp_dir.hpp"

using stage1::benchmark_index_disk;
using stage1::SampleBook;
using stage1::testing::TempDir;

namespace {

const std::vector<SampleBook> kCorpus = {
    {1342, "the whale swims near the island.", ""},
    {84, "the boat sails to the island.", ""},
};
const std::unordered_set<std::string> kStopwords = {"the", "to", "near"};

}  // namespace

TEST(BenchmarkIndexDisk, MonolithicAndHierarchicalAgreeOnTermsAndPostings) {
    TempDir root("stage1_index_disk_benchmark_test");

    auto results = benchmark_index_disk("cpp", kCorpus, kStopwords, root.path());

    double monolithic_terms = -1, hierarchical_terms = -1;
    double monolithic_postings = -1, hierarchical_postings = -1;
    double monolithic_bytes = -1, hierarchical_bytes = -1;
    double monolithic_files = -1, hierarchical_files = -1;
    for (const auto& result : results) {
        EXPECT_EQ(result.language, "cpp");
        EXPECT_EQ(result.experiment, "index_disk");
        EXPECT_EQ(result.dataset_size, 2);
        EXPECT_GE(result.value, 0.0);
        if (result.structure == "monolithic" && result.metric == "terms") monolithic_terms = result.value;
        if (result.structure == "hierarchical" && result.metric == "terms") hierarchical_terms = result.value;
        if (result.structure == "monolithic" && result.metric == "postings") monolithic_postings = result.value;
        if (result.structure == "hierarchical" && result.metric == "postings") hierarchical_postings = result.value;
        if (result.structure == "monolithic" && result.metric == "bytes") monolithic_bytes = result.value;
        if (result.structure == "hierarchical" && result.metric == "bytes") hierarchical_bytes = result.value;
        if (result.structure == "monolithic" && result.metric == "files") monolithic_files = result.value;
        if (result.structure == "hierarchical" && result.metric == "files") hierarchical_files = result.value;
    }

    // Same logical data, same term/postings counts regardless of structure.
    EXPECT_EQ(monolithic_terms, hierarchical_terms);
    EXPECT_GT(monolithic_terms, 0.0);
    EXPECT_EQ(monolithic_postings, hierarchical_postings);
    EXPECT_GT(monolithic_postings, 0.0);

    // Physically different: one file vs. one file per term.
    EXPECT_EQ(monolithic_files, 1.0);
    EXPECT_EQ(hierarchical_files, monolithic_terms);
    EXPECT_GT(monolithic_bytes, 0.0);
    EXPECT_GT(hierarchical_bytes, 0.0);
}
