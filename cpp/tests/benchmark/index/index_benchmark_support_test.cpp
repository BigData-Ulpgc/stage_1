#include <gtest/gtest.h>

#include <stdexcept>

#include "stage1/benchmark/index/index_benchmark_support.hpp"

using stage1::build_index;
using stage1::SampleBook;
using stage1::tokenize_all;
using stage1::verify_index;

namespace {

const std::vector<SampleBook> kCorpus = {
    {1, "The whale swims; the WHALE dives near the island.", ""},
    {2, "The boat sails to the island.", ""},
};
const std::unordered_set<std::string> kStopwords = {"the", "to", "near"};
const std::vector<std::string> kQueries = {"whale island", "boat"};

}  // namespace

TEST(TokenizeAll, KeepsEachBooksDistinctTermsSortedAndItsId) {
    const auto books = tokenize_all(kCorpus, kStopwords);

    ASSERT_EQ(books.size(), 2u);
    EXPECT_EQ(books[0].book_id, 1);
    EXPECT_EQ(books[0].terms, (std::vector<std::string>{"dives", "island", "swims", "whale"}));  // "whale" once
    EXPECT_EQ(books[1].terms, (std::vector<std::string>{"boat", "island", "sails"}));
}

TEST(BuildIndex, IndexesEveryTokenizedBook) {
    const auto index = build_index(tokenize_all(kCorpus, kStopwords));

    EXPECT_EQ(index.postings("island"), (std::vector<int>{1, 2}));
    EXPECT_EQ(index.postings("whale"), (std::vector<int>{1}));
}

TEST(VerifyIndex, AcceptsAStructureThatMatchesTheReference) {
    const auto books = tokenize_all(kCorpus, kStopwords);
    const auto index = build_index(books);

    EXPECT_NO_THROW(verify_index([&](const std::string& t) { return index.postings(t); }, books, kQueries, kStopwords,
                                 "test"));
}

TEST(VerifyIndex, RejectsAStructureWithWrongPostings) {
    const auto books = tokenize_all(kCorpus, kStopwords);
    const auto index = build_index(books);
    const auto missing_book_2 = [&](const std::string& term) {
        auto postings = index.postings(term);
        postings.erase(std::remove(postings.begin(), postings.end(), 2), postings.end());
        return postings;
    };

    EXPECT_THROW(verify_index(missing_book_2, books, kQueries, kStopwords, "test"), std::runtime_error);
}
