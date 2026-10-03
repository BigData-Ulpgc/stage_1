#include "stage1/benchmark/datalake_lookup_benchmark.hpp"

#include <algorithm>
#include <stdexcept>

#include "stage1/benchmark/datalake_benchmark_support.hpp"
#include "stage1/benchmark/java_random.hpp"

namespace stage1 {

namespace {

// Java's shuffledIds: the distinct ids in ascending order, then
// Collections.shuffle with new Random(42). Every structure looks the books up
// in this same order, the same order Java uses.
std::vector<int> shuffled_ids(const std::vector<SampleBook>& books) {
    std::vector<int> ids;
    for (const auto& book : books) {
        ids.push_back(book.book_id);
    }
    std::sort(ids.begin(), ids.end());
    ids.erase(std::unique(ids.begin(), ids.end()), ids.end());
    JavaRandom random(42);
    java_shuffle(ids, random);
    return ids;
}

}  // namespace

std::vector<BenchmarkResult> benchmark_datalake_lookup(const std::string& language,
                                                         const std::vector<SampleBook>& books,
                                                         const std::filesystem::path& output_dir) {
    const auto ids = shuffled_ids(books);
    const int dataset_size = static_cast<int>(books.size());
    std::vector<BenchmarkResult> results;

    for (const auto& structure : kDatalakeStructures) {
        // Written once, untimed: this experiment measures locate(), not write().
        const auto datalake = fresh_datalake(structure, output_dir / structure);
        for (const auto& book : books) {
            datalake->write(book.book_id, book.header, book.body);
        }

        std::size_t found = 0;
        const auto elapsed_ms = measure_elapsed_ms([&] { found = 0; },
                                                   [&] {
                                                       for (int id : ids) {
                                                           if (datalake->locate(id)) {
                                                               ++found;  // uses the result, as Java does
                                                           }
                                                       }
                                                   });
        if (found != ids.size()) {
            throw std::runtime_error(structure + ": locate did not find every book");
        }

        const auto elapsed = elapsed_rows(language, "datalake_lookup", structure, dataset_size, elapsed_ms);
        const auto per_lookup =
            derived_rows(elapsed, "per_lookup", "us", [&](double ms) { return ms * 1000.0 / ids.size(); });
        results.insert(results.end(), elapsed.begin(), elapsed.end());
        results.insert(results.end(), per_lookup.begin(), per_lookup.end());
    }
    return results;
}

}  // namespace stage1
