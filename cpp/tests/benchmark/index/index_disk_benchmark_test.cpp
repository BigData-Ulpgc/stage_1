#include <gtest/gtest.h>

#include "stage1/benchmark/index/index_disk_benchmark.hpp"
#include "stage1/datamart/index/mongo_index_writer.hpp"
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
const std::vector<std::string> kQueries = {"whale island", "boat"};

}  // namespace

TEST(BenchmarkIndexDisk, MonolithicAndHierarchicalAgreeOnTermsAndPostings) {
    TempDir root("stage1_index_disk_benchmark_test");

    auto results = benchmark_index_disk("cpp", kCorpus, kQueries, kStopwords, root.path());

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

TEST(BenchmarkIndexDisk, ReportsJavasFiveMetricsInJavasOrder) {
    TempDir root("stage1_index_disk_benchmark_test_order");

    auto results = benchmark_index_disk("cpp", kCorpus, kQueries, kStopwords, root.path());

    std::vector<std::string> hierarchical_metrics;
    double bytes = 0, allocated = 0;
    for (const auto& r : results) {
        if (r.structure != "hierarchical") continue;
        hierarchical_metrics.push_back(r.metric);
        if (r.metric == "bytes") bytes = r.value;
        if (r.metric == "allocated_bytes") allocated = r.value;
    }
    EXPECT_EQ(hierarchical_metrics,
              (std::vector<std::string>{"bytes", "files", "allocated_bytes", "terms", "postings"}));
    EXPECT_GT(allocated, bytes);  // a handful of tiny files still reserve whole blocks each
}

TEST(BenchmarkIndexDisk, ReportsMongoWithJavasRowsWhenReachable) {
    if (!stage1::mongo_is_reachable()) {
        GTEST_SKIP() << "no MongoDB reachable (start it with `docker compose up -d`)";
    }
    TempDir root("stage1_index_disk_benchmark_test_mongo");

    auto results = benchmark_index_disk("cpp", kCorpus, kQueries, kStopwords, root.path());

    std::vector<std::string> mongo_metrics;
    for (const auto& r : results) {
        if (r.structure != "mongo") continue;
        mongo_metrics.push_back(r.metric);
        if (r.metric == "bytes") EXPECT_GT(r.value, 0.0);
    }
    EXPECT_EQ(mongo_metrics, (std::vector<std::string>{"bytes", "terms", "postings"}));
}
