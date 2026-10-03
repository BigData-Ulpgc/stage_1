#include <gtest/gtest.h>

#include "stage1/tokenizer.hpp"

using stage1::tokenize;
using Tokens = std::vector<std::string>;

TEST(Tokenizer, LowercasesAndSplitsOnPunctuation) {
    // The apostrophe is a separator, so "Adventure's" gives "adventure" + "s";
    // the lone "s" is then dropped for being shorter than 2 characters.
    EXPECT_EQ(tokenize("The Adventure's of Tom, 1876!"), (Tokens{"the", "adventure", "of", "tom", "1876"}));
}

TEST(Tokenizer, NonAsciiBytesAreSeparators) {
    // "é" is two non-ASCII bytes in UTF-8, so it cuts "café" after "caf".
    EXPECT_EQ(tokenize("The Dog's café, 2 cats!"), (Tokens{"the", "dog", "caf", "cats"}));
}

TEST(Tokenizer, EmptyAndSeparatorOnlyInputGiveNoTokens) {
    EXPECT_TRUE(tokenize("").empty());
    EXPECT_TRUE(tokenize("  ,.;!? \n\t").empty());
}

TEST(Tokenizer, LastTokenIsKeptWithoutTrailingSeparator) {
    EXPECT_EQ(tokenize("hello world"), (Tokens{"hello", "world"}));
}

TEST(Tokenizer, DropsTokensShorterThanTwoCharacters) {
    EXPECT_EQ(tokenize("a 1 22 b7"), (Tokens{"22", "b7"}));
}

TEST(Tokenizer, RemovesStopwords) {
    const std::unordered_set<std::string> stopwords = {"the", "is", "that"};
    EXPECT_EQ(tokenize("The Dog's café, 2 cats!", stopwords), (Tokens{"dog", "caf", "cats"}));
    // Theory example: 001 "the car is nice" -> only "car" and "nice" reach the index.
    EXPECT_EQ(tokenize("the car is nice", stopwords), (Tokens{"car", "nice"}));
}

TEST(Tokenizer, StopwordsAreMatchedAfterLowercasing) {
    EXPECT_EQ(tokenize("THE Car", {"the"}), (Tokens{"car"}));
}

TEST(Tokenizer, EmptyStopwordSetKeepsEverything) {
    EXPECT_EQ(tokenize("the car", std::unordered_set<std::string>{}), tokenize("the car"));
}

TEST(Tokenizer, StopwordsOnlyMatchWholeTokens) {
    // "theory" contains "the" but is a different token, so it must survive.
    EXPECT_EQ(tokenize("theory the", {"the"}), (Tokens{"theory"}));
}
