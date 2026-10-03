#include "stage1/benchmark/java_random.hpp"

#include <limits>
#include <stdexcept>

namespace stage1 {

namespace {

constexpr std::uint64_t kMultiplier = 0x5DEECE66DULL;
constexpr std::uint64_t kAddend = 0xBULL;
constexpr std::uint64_t kMask = (1ULL << 48) - 1;  // the generator keeps 48 bits of state

}  // namespace

JavaRandom::JavaRandom(std::int64_t seed) : seed_((static_cast<std::uint64_t>(seed) ^ kMultiplier) & kMask) {}

int JavaRandom::next(int bits) {
    seed_ = (seed_ * kMultiplier + kAddend) & kMask;  // unsigned: wraps around exactly like Java's long
    return static_cast<int>(seed_ >> (48 - bits));    // at most 31 bits: always fits in an int
}

int JavaRandom::next_int(int bound) {
    if (bound <= 0) {
        throw std::invalid_argument("JavaRandom::next_int: bound must be positive");
    }
    int r = next(31);
    const int m = bound - 1;
    if ((bound & m) == 0) {  // bound is a power of 2: take the high bits
        return static_cast<int>((static_cast<std::int64_t>(bound) * r) >> 31);
    }
    // Java rejects the values that would make the result biased, detecting them
    // with `u - r + m < 0`, which relies on 32-bit int overflow. Signed overflow
    // is undefined behaviour in C++, so the sum is done in 64 bits and compared
    // with the largest int instead.
    for (std::int64_t u = r; u - (r = static_cast<int>(u % bound)) + m > std::numeric_limits<int>::max();
         u = next(31)) {
    }
    return r;
}

}  // namespace stage1
