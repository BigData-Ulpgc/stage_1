#include "stage1/benchmark/datalake_recovery_benchmark.hpp"

#include <memory>
#include <stdexcept>
#include <unordered_set>

#include "stage1/benchmark/datalake_benchmark_support.hpp"
#include "stage1/util/file_io.hpp"

namespace stage1 {

namespace {

// Java's everyTenth: the books at positions 0, 10, 20, ...
std::vector<SampleBook> every_tenth(const std::vector<SampleBook>& books) {
    std::vector<SampleBook> damaged;
    for (std::size_t i = 0; i < books.size(); i += 10) {
        damaged.push_back(books[i]);
    }
    return damaged;
}

// Java's interruptBeforeBodyMove: what a process that died after writing the
// body to body.txt.tmp, and before renaming it, would leave behind.
void interrupt_before_body_move(const BookLocation& location) {
    std::filesystem::rename(location.body_path, location.body_path + ".tmp");
}

}  // namespace

std::vector<BenchmarkResult> benchmark_datalake_recovery(const std::string& language,
                                                           const std::vector<SampleBook>& books,
                                                           const std::filesystem::path& output_dir) {
    const auto damaged = every_tenth(books);
    const int dataset_size = static_cast<int>(books.size());
    std::vector<BenchmarkResult> results;

    for (const auto& structure : kDatalakeStructures) {
        const auto dir = output_dir / structure;
        std::unique_ptr<Datalake> datalake;
        std::size_t recovered = 0;
        const auto elapsed_ms = measure_elapsed_ms(
            [&] {  // untimed, every repetition: every book saved, then the crash
                datalake = fresh_datalake(structure, dir);
                for (const auto& book : books) {
                    datalake->write(book.book_id, book.header, book.body);
                }
                for (const auto& book : damaged) {
                    interrupt_before_body_move(*datalake->locate(book.book_id));
                }
                recovered = 0;
            },
            [&] {  // timed: find what is missing and save it again
                const auto present_ids = datalake->list_book_ids();
                const std::unordered_set<int> present(present_ids.begin(), present_ids.end());
                for (const auto& book : books) {
                    if (present.count(book.book_id) == 0) {
                        datalake->write(book.book_id, book.header, book.body);
                        ++recovered;
                    }
                }
            });

        // Checked on the final state (every repetition starts from the same crash).
        const auto listed_ids = datalake->list_book_ids();
        const std::unordered_set<int> listed(listed_ids.begin(), listed_ids.end());
        long long lost = 0;
        for (const auto& book : books) {
            if (listed.count(book.book_id) == 0) {
                ++lost;
            }
        }
        const long long duplicates = count_files_with_suffix(dir, "body.txt") - dataset_size;  // extra complete bodies
        if (recovered != damaged.size()) {
            throw std::runtime_error(structure + ": recovery did not save every damaged book again");
        }
        if (lost != 0 || duplicates != 0) {
            throw std::runtime_error(structure + ": recovery left lost or duplicated books");
        }

        const auto elapsed = elapsed_rows(language, "datalake_recovery", structure, dataset_size, elapsed_ms);
        results.insert(results.end(), elapsed.begin(), elapsed.end());
        results.push_back(BenchmarkResult{language, "datalake_recovery", structure, dataset_size, 1, "recovered",
                                           static_cast<double>(recovered), "books"});
        results.push_back(BenchmarkResult{language, "datalake_recovery", structure, dataset_size, 1, "lost",
                                           static_cast<double>(lost), "books"});
        results.push_back(BenchmarkResult{language, "datalake_recovery", structure, dataset_size, 1, "duplicates",
                                           static_cast<double>(duplicates), "books"});
    }
    return results;
}

}  // namespace stage1
