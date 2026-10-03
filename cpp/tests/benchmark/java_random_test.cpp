#include <gtest/gtest.h>

#include <stdexcept>
#include <vector>

#include "stage1/benchmark/java_random.hpp"

using stage1::java_shuffle;
using stage1::JavaRandom;

// Every expected value below was printed by a real JVM (OpenJDK 23) running
// java.util.Random with the same seeds and calls (see DEVLOG Entry 57).

namespace {

std::vector<int> draw(JavaRandom random, int bound, int count) {
    std::vector<int> values;
    for (int i = 0; i < count; ++i) {
        values.push_back(random.next_int(bound));
    }
    return values;
}

}  // namespace

TEST(JavaRandom, MatchesJavaForAnOrdinaryBound) {
    EXPECT_EQ(draw(JavaRandom(42), 100, 8), (std::vector<int>{30, 63, 48, 84, 70, 25, 5, 18}));
}

TEST(JavaRandom, MatchesJavaForAPowerOfTwoBound) {
    // Java takes a different branch here: the high bits of next(31).
    EXPECT_EQ(draw(JavaRandom(42), 16, 8), (std::vector<int>{11, 0, 10, 0, 4, 15, 4, 11}));
}

TEST(JavaRandom, MatchesJavaWhenItHasToRejectValues) {
    // 2^30 + 1: about half of all draws are rejected, which exercises the loop
    // Java writes with 32-bit int overflow (done in 64 bits here).
    EXPECT_EQ(draw(JavaRandom(42), 1073741825, 8),
              (std::vector<int>{117392763, 102948884, 662969970, 595021505, 196118093, 969067502, 791955276,
                                819572292}));
}

TEST(JavaRandom, MatchesJavaForANegativeSeed) {
    EXPECT_EQ(draw(JavaRandom(-7), 1000, 5), (std::vector<int>{662, 297, 590, 707, 478}));
}

TEST(JavaRandom, ShuffleMatchesJavasCollectionsShuffle) {
    std::vector<int> ids = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    JavaRandom random(42);

    java_shuffle(ids, random);

    EXPECT_EQ(ids, (std::vector<int>{5, 7, 3, 2, 8, 10, 9, 6, 4, 1}));
}

TEST(JavaRandom, ANonPositiveBoundThrows) {
    JavaRandom random(42);
    EXPECT_THROW(random.next_int(0), std::invalid_argument);
}
