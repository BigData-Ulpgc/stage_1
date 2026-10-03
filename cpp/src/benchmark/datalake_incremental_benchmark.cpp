#include "stage1/benchmark/datalake_incremental_benchmark.hpp"

#include <algorithm>
#include <memory>
#include <set>
#include <stdexcept>

#include "stage1/benchmark/datalake_benchmark_support.hpp"

namespace stage1 {

namespace {

std::set<int> ids_of(const std::vector<SampleBook>& books) {
    std::set<int> ids;
    for (const auto& book : books) {
        ids.insert(book.book_id);
    }
    return ids;
}

}  // namespace

std::vector<BenchmarkResult> benchmark_datalake_incremental(const std::string& language,
                                                              const std::vector<SampleBook>& books,
                                                              const std::filesystem::path& output_dir) {
    if (books.size() < 2) {
        throw std::invalid_argument("benchmark_datalake_incremental needs at least 2 books");
    }

    // The last 10% (at least one book) are "fresh", the rest already "known":
    // Java's split.
    const std::size_t fresh_count = std::max<std::size_t>(1, books.size() / 10);
    const std::vector<SampleBook> known(books.begin(), books.end() - static_cast<std::ptrdiff_t>(fresh_count));
    const std::vector<SampleBook> fresh(books.end() - static_cast<std::ptrdiff_t>(fresh_count), books.end());
    const std::set<int> known_ids = ids_of(known);
    const std::set<int> fresh_ids = ids_of(fresh);

    const int dataset_size = static_cast<int>(books.size());
    std::vector<BenchmarkResult> results;

    for (const auto& structure : kDatalakeStructures) {
        const auto dir = output_dir / structure;
        std::unique_ptr<Datalake> datalake;
        std::set<int> detected;
        const auto elapsed_ms = measure_elapsed_ms(
            [&] {  // untimed, every repetition: the known books, then the fresh ones "arrive"
                datalake = fresh_datalake(structure, dir);
                for (const auto& book : known) {
                    datalake->write(book.book_id, book.header, book.body);
                }
                for (const auto& book : fresh) {
                    datalake->write(book.book_id, book.header, book.body);
                }
            },
            [&] {  // timed: everything listed, minus the known ids (Java's TreeSet + removeAll)
                const auto listed = datalake->list_book_ids();
                std::set<int> all(listed.begin(), listed.end());
                for (int id : known_ids) {
                    all.erase(id);
                }
                detected = std::move(all);
            });
        if (detected != fresh_ids) {
            throw std::runtime_error(structure + ": incremental detection did not find exactly the fresh books");
        }

        const auto elapsed = elapsed_rows(language, "datalake_incremental", structure, dataset_size, elapsed_ms);
        const auto detected_rows = derived_rows(elapsed, "detected", "books",
                                                [&](double) { return static_cast<double>(detected.size()); });
        results.insert(results.end(), elapsed.begin(), elapsed.end());
        results.insert(results.end(), detected_rows.begin(), detected_rows.end());
    }
    return results;
}

}  // namespace stage1
