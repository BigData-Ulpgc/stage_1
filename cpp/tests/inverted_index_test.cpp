#include <gtest/gtest.h>

#include "stage1/inverted_index.hpp"
#include "stage1/tokenizer.hpp"

using stage1::InvertedIndex;
using stage1::tokenize;

TEST(InvertedIndex, StartsEmpty) {
    InvertedIndex index;
    EXPECT_EQ(index.term_count(), 0u);
    EXPECT_TRUE(index.postings("car").empty());
}

TEST(InvertedIndex, AddingABookIndexesEachOfItsTerms) {
    InvertedIndex index;
    index.add_book(1, {"car", "nice"});

    EXPECT_EQ(index.postings("car"), std::vector<int>{1});
    EXPECT_EQ(index.postings("nice"), std::vector<int>{1});
    EXPECT_EQ(index.term_count(), 2u);
}

TEST(InvertedIndex, UnknownTermHasEmptyPostings) {
    InvertedIndex index;
    index.add_book(1, {"car"});
    EXPECT_TRUE(index.postings("dragon").empty());
}

TEST(InvertedIndex, PostingsAreSortedRegardlessOfInsertionOrder) {
    InvertedIndex index;
    index.add_book(3, {"car"});
    index.add_book(1, {"car"});
    index.add_book(2, {"car"});

    EXPECT_EQ(index.postings("car"), (std::vector<int>{1, 2, 3}));
}

TEST(InvertedIndex, RepeatingATermWithinOneBookCountsItOnce) {
    InvertedIndex index;
    index.add_book(1, {"car", "car", "car"});

    EXPECT_EQ(index.postings("car"), std::vector<int>{1});
    EXPECT_EQ(index.term_count(), 1u);
}

TEST(InvertedIndex, TheSameTermFromDifferentBooksIsNotDuplicated) {
    InvertedIndex index;
    index.add_book(1, {"car"});
    index.add_book(1, {"car"});  // re-indexing book 1 must not add a duplicate id

    EXPECT_EQ(index.postings("car"), std::vector<int>{1});
}

TEST(InvertedIndex, TermCountReflectsDistinctTermsOnly) {
    InvertedIndex index;
    index.add_book(1, {"car", "car", "nice"});
    index.add_book(2, {"car", "boat"});

    EXPECT_EQ(index.term_count(), 3u);  // car, nice, boat
}

TEST(InvertedIndex, WorksDirectlyWithTokenizeOutput) {
    // The theory example from the start of the project, now closing the loop:
    // 001 "the car is nice" with stopwords {the, is, that} indexes to car/nice.
    InvertedIndex index;
    const std::unordered_set<std::string> stopwords = {"the", "is", "that"};

    index.add_book(1, tokenize("the car is nice", stopwords));
    index.add_book(2, tokenize("that car is mine", stopwords));
    index.add_book(3, tokenize("the car is the best", stopwords));

    EXPECT_EQ(index.postings("car"), (std::vector<int>{1, 2, 3}));
    EXPECT_EQ(index.postings("nice"), std::vector<int>{1});
    EXPECT_EQ(index.postings("best"), std::vector<int>{3});
}
