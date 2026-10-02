#include <gtest/gtest.h>

#include "stage1/datamart/index/inverted_index.hpp"
#include "stage1/query/query_engine.hpp"
#include "stage1/datamart/index/tokenizer.hpp"

using stage1::InvertedIndex;
using stage1::query_and;
using stage1::tokenize;

TEST(QueryEngine, SingleTermReturnsItsPostings) {
    InvertedIndex index;
    index.add_book(3, {"car"});
    index.add_book(1, {"car"});

    EXPECT_EQ(query_and(index, {"car"}), (std::vector<int>{1, 3}));
}

TEST(QueryEngine, TwoTermsReturnTheirIntersection) {
    InvertedIndex index;
    index.add_book(1, {"car", "nice"});
    index.add_book(2, {"car"});
    index.add_book(3, {"car", "nice"});

    EXPECT_EQ(query_and(index, {"car", "nice"}), (std::vector<int>{1, 3}));
}

TEST(QueryEngine, AnUnknownTermMakesTheWholeQueryEmpty) {
    InvertedIndex index;
    index.add_book(1, {"car", "nice"});

    EXPECT_TRUE(query_and(index, {"car", "dragon"}).empty());
}

TEST(QueryEngine, EmptyTermListReturnsNoResults) {
    InvertedIndex index;
    index.add_book(1, {"car"});

    EXPECT_TRUE(query_and(index, {}).empty());
}

TEST(QueryEngine, RepeatingATermInTheQueryBehavesLikeOnce) {
    InvertedIndex index;
    index.add_book(1, {"car"});
    index.add_book(2, {"car"});

    EXPECT_EQ(query_and(index, {"car", "car"}), query_and(index, {"car"}));
}

TEST(QueryEngine, ResultStaysSortedWithThreeTerms) {
    InvertedIndex index;
    index.add_book(5, {"car", "nice", "boat"});
    index.add_book(2, {"car", "nice", "boat"});
    index.add_book(9, {"car", "nice", "boat"});
    index.add_book(2, {"car"});  // already in the index; must not duplicate

    EXPECT_EQ(query_and(index, {"boat", "car", "nice"}), (std::vector<int>{2, 5, 9}));
}

TEST(QueryEngine, WorksDirectlyWithTokenizeOutputOnTheOriginalTheoryExample) {
    // The very first exercise of this project: 001 "the car is nice",
    // 002 "that car is mine", 003 "the car is the best". Querying "car nice"
    // with AND must return only 001.
    InvertedIndex index;
    const std::unordered_set<std::string> stopwords = {"the", "is", "that"};

    index.add_book(1, tokenize("the car is nice", stopwords));
    index.add_book(2, tokenize("that car is mine", stopwords));
    index.add_book(3, tokenize("the car is the best", stopwords));

    EXPECT_EQ(query_and(index, tokenize("car nice", stopwords)), std::vector<int>{1});
}
