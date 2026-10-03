#include <gtest/gtest.h>

#include <stdexcept>

#include "stage1/datamart/index/hierarchical_index_writer.hpp"
#include "stage1/datamart/index/index_readers.hpp"
#include "stage1/datamart/index/inverted_index.hpp"
#include "stage1/datamart/index/monolithic_index_writer.hpp"
#include "stage1/query/query_engine.hpp"
#include "support/temp_dir.hpp"

using stage1::hierarchical_postings_fetcher;
using stage1::HierarchicalIndexWriter;
using stage1::InvertedIndex;
using stage1::monolithic_postings_fetcher;
using stage1::MonolithicIndexWriter;
using stage1::query_and;
using stage1::testing::TempDir;

namespace {

InvertedIndex sample_index() {
    InvertedIndex index;
    index.add_book(3, {"car", "boat"});
    index.add_book(1, {"car", "nice"});
    return index;
}

}  // namespace

TEST(MonolithicPostingsFetcher, ReadsBackWhatTheWriterWrote) {
    TempDir root("stage1_index_readers_test_monolithic");
    const auto path = root.path() / "inverted_index.json";
    MonolithicIndexWriter(path).write(sample_index());

    const auto postings = monolithic_postings_fetcher(path);

    EXPECT_EQ(postings("car"), (std::vector<int>{1, 3}));
    EXPECT_EQ(postings("nice"), (std::vector<int>{1}));
    EXPECT_TRUE(postings("dragon").empty());  // unknown term: no postings, not an error
}

TEST(MonolithicPostingsFetcher, MissingFileThrows) {
    TempDir root("stage1_index_readers_test_missing");

    EXPECT_THROW(monolithic_postings_fetcher(root.path() / "inverted_index.json"), std::runtime_error);
}

TEST(HierarchicalPostingsFetcher, ReadsBackWhatTheWriterWrote) {
    TempDir root("stage1_index_readers_test_hierarchical");
    HierarchicalIndexWriter(root.path()).write(sample_index());

    const auto postings = hierarchical_postings_fetcher(root.path());

    EXPECT_EQ(postings("car"), (std::vector<int>{1, 3}));
    EXPECT_EQ(postings("boat"), (std::vector<int>{3}));
    EXPECT_TRUE(postings("dragon").empty());  // no file for it: no postings, not an error
}

TEST(IndexReaders, AnswerAndQueriesExactlyLikeTheInMemoryIndex) {
    TempDir root("stage1_index_readers_test_query");
    const auto index = sample_index();
    MonolithicIndexWriter(root.path() / "inverted_index.json").write(index);
    HierarchicalIndexWriter(root.path() / "inverted_index").write(index);

    const auto monolithic = monolithic_postings_fetcher(root.path() / "inverted_index.json");
    const auto hierarchical = hierarchical_postings_fetcher(root.path() / "inverted_index");

    const std::vector<std::vector<std::string>> queries = {{"car"}, {"car", "nice"}, {"car", "boat"}, {"car", "dragon"}};
    for (const auto& terms : queries) {
        const auto expected = query_and(index, terms);
        EXPECT_EQ(query_and(monolithic, terms), expected);
        EXPECT_EQ(query_and(hierarchical, terms), expected);
    }
}
