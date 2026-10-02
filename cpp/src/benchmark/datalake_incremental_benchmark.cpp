#include "stage1/benchmark/datalake_incremental_benchmark.hpp"

#include <algorithm>
#include <stdexcept>
#include <unordered_set>

#include "stage1/datalake/book_based_datalake.hpp"
#include "stage1/datalake/range_based_datalake.hpp"
#include "stage1/datalake/time_based_datalake.hpp"

namespace stage1 {

namespace {

// Writes `known` then `fresh` through `datalake` (untimed: this experiment
// measures detection, not writing), then times "list everything, subtract
// the known ids". Checks the result is exactly `fresh`'s ids every
// repetition, the same correctness check the Java module's equivalent
// benchmark makes, so a bug in list_book_ids() shows up here, not just as a
// suspiciously fast or slow number.
void run_and_record(const std::string& language, const std::string& structure, const std::vector<SampleBook>& known,
                     const std::vector<SampleBook>& fresh, Datalake& datalake,
                     std::vector<BenchmarkResult>& results) {
    std::unordered_set<int> known_ids;
    for (const auto& book : known) {
        datalake.write(book.book_id, book.header, book.body);
        known_ids.insert(book.book_id);
    }
    std::unordered_set<int> fresh_ids;
    for (const auto& book : fresh) {
        datalake.write(book.book_id, book.header, book.body);
        fresh_ids.insert(book.book_id);
    }

    std::unordered_set<int> detected;
    const auto elapsed = measure_elapsed_ms([&] {
        detected.clear();
        for (int id : datalake.list_book_ids()) {
            if (known_ids.count(id) == 0) {
                detected.insert(id);
            }
        }
    });

    if (detected != fresh_ids) {
        throw std::runtime_error(structure + ": incremental detection did not find exactly the fresh books");
    }

    const int dataset_size = static_cast<int>(known.size() + fresh.size());
    int repetition = 1;
    for (double ms : elapsed) {
        results.push_back(BenchmarkResult{language, "datalake_incremental", structure, dataset_size, repetition++,
                                           "elapsed", ms, "ms"});
    }
}

}  // namespace

std::vector<BenchmarkResult> benchmark_datalake_incremental(const std::string& language,
                                                              const std::vector<SampleBook>& books,
                                                              const std::filesystem::path& output_dir) {
    if (books.size() < 2) {
        throw std::invalid_argument("benchmark_datalake_incremental needs at least 2 books");
    }

    // The most recent 10% (at least one book) are "fresh"; the rest are
    // already "known" -- same split the Java module's own benchmark uses.
    const std::size_t fresh_count = std::max<std::size_t>(1, books.size() / 10);
    const std::vector<SampleBook> known(books.begin(), books.end() - static_cast<std::ptrdiff_t>(fresh_count));
    const std::vector<SampleBook> fresh(books.end() - static_cast<std::ptrdiff_t>(fresh_count), books.end());

    std::vector<BenchmarkResult> results;

    BookBasedDatalake book_datalake(output_dir / "book");
    run_and_record(language, "book", known, fresh, book_datalake, results);

    RangeBasedDatalake range_datalake(output_dir / "range");
    run_and_record(language, "range", known, fresh, range_datalake, results);

    SystemClock clock;
    TimeBasedDatalake time_datalake(output_dir / "time", clock);
    run_and_record(language, "time", known, fresh, time_datalake, results);

    return results;
}

}  // namespace stage1
