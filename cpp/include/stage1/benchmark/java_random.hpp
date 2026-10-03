#pragma once

#include <cstddef>
#include <cstdint>
#include <utility>
#include <vector>

namespace stage1 {

// java.util.Random, reproduced number for number. Its algorithm is part of the
// Java API specification: a 48-bit linear congruential generator. The Java
// module's benchmarks make their "random" choices with it and a fixed seed
// (the order of datalake_lookup, the metadata_query workload). With this class
// both languages make exactly the same choices, which is the "same data"
// condition of a fair comparison. (std::mt19937 with the same seed would give
// a different sequence, DEVLOG Entry 46.)
class JavaRandom {
public:
    // new java.util.Random(seed)
    explicit JavaRandom(std::int64_t seed);

    // Random.nextInt(int bound): uniform in [0, bound). `bound` must be > 0.
    int next_int(int bound);

private:
    // Random.next(int bits): the generator's step.
    int next(int bits);

    std::uint64_t seed_;
};

// Collections.shuffle(list, random) for a random-access list such as Java's
// ArrayList: the same swaps in the same order as Java.
template <typename T>
void java_shuffle(std::vector<T>& items, JavaRandom& random) {
    for (std::size_t i = items.size(); i > 1; --i) {
        std::swap(items[i - 1], items[static_cast<std::size_t>(random.next_int(static_cast<int>(i)))]);
    }
}

}  // namespace stage1
