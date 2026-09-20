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
