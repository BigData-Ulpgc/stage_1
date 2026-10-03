#include "stage1/benchmark/datalake_recovery_benchmark.hpp"

#include <stdexcept>
#include <unordered_set>

#include "stage1/datalake/book_based_datalake.hpp"
#include "stage1/util/file_io.hpp"
#include "stage1/datalake/range_based_datalake.hpp"
#include "stage1/datalake/time_based_datalake.hpp"

namespace stage1 {

namespace {

// Every 10th book (indices 9, 19, 29, ...), the same "damage" sample Java's
// equivalent benchmark uses.
std::vector<SampleBook> every_tenth(const std::vector<SampleBook>& books) {
    std::vector<SampleBook> damaged;
    for (std::size_t i = 9; i < books.size(); i += 10) {
        damaged.push_back(books[i]);
    }
    return damaged;
}

void run_and_record(const std::string& language, const std::string& structure, const std::vector<SampleBook>& books,
                     const std::vector<SampleBook>& damaged, Datalake& datalake,
                     const std::filesystem::path& dir, std::vector<BenchmarkResult>& results) {
    const auto setup = [&] {
        std::filesystem::remove_all(dir);
        for (const auto& book : books) {
            datalake.write(book.book_id, book.header, book.body);
        }
        for (const auto& book : damaged) {
            const auto location = datalake.locate(book.book_id);
            std::filesystem::remove(location->header_path);  // simulates the crash
        }
    };

    const auto elapsed = measure_elapsed_ms(setup, [&] {
        const auto present_ids = datalake.list_book_ids();
        const std::unordered_set<int> present(present_ids.begin(), present_ids.end());
        for (const auto& book : books) {
            if (present.count(book.book_id) == 0) {
                datalake.write(book.book_id, book.header, book.body);
            }
        }
    });

    // The state left by the final repetition (every repetition starts from
    // the same damage, so any one of them is representative).
    const auto final_present_ids = datalake.list_book_ids();
    const std::unordered_set<int> final_present(final_present_ids.begin(), final_present_ids.end());
    for (const auto& book : books) {
        if (final_present.count(book.book_id) == 0) {
            throw std::runtime_error(structure + ": recovery left book " + std::to_string(book.book_id) + " missing");
        }
    }
    const int duplicates = count_files_with_suffix(dir, "body.txt") - static_cast<int>(books.size());
    if (duplicates != 0) {
        throw std::runtime_error(structure + ": recovery left " + std::to_string(duplicates) + " duplicate body file(s)");
    }

    const int dataset_size = static_cast<int>(books.size());
    int repetition = 1;
    for (double ms : elapsed) {
        results.push_back(BenchmarkResult{language, "datalake_recovery", structure, dataset_size, repetition++,
                                           "elapsed", ms, "ms"});
    }
    results.push_back(BenchmarkResult{language, "datalake_recovery", structure, dataset_size, 1, "recovered",
                                       static_cast<double>(damaged.size()), "books"});
    results.push_back(
        BenchmarkResult{language, "datalake_recovery", structure, dataset_size, 1, "duplicates", 0.0, "books"});
}

}  // namespace

std::vector<BenchmarkResult> benchmark_datalake_recovery(const std::string& language,
                                                           const std::vector<SampleBook>& books,
                                                           const std::filesystem::path& output_dir) {
    const auto damaged = every_tenth(books);
    if (damaged.empty()) {
        throw std::invalid_argument("benchmark_datalake_recovery needs at least 10 books");
    }

    std::vector<BenchmarkResult> results;

    const auto book_dir = output_dir / "book";
    BookBasedDatalake book_datalake(book_dir);
    run_and_record(language, "book", books, damaged, book_datalake, book_dir, results);

    const auto range_dir = output_dir / "range";
    RangeBasedDatalake range_datalake(range_dir);
    run_and_record(language, "range", books, damaged, range_datalake, range_dir, results);

    const auto time_dir = output_dir / "time";
    SystemClock clock;
    TimeBasedDatalake time_datalake(time_dir, clock);
    run_and_record(language, "time", books, damaged, time_datalake, time_dir, results);

    return results;
}

}  // namespace stage1
