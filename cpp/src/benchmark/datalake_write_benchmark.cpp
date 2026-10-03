#include "stage1/benchmark/datalake_write_benchmark.hpp"

#include <memory>

#include "stage1/benchmark/datalake_benchmark_support.hpp"

namespace stage1 {

std::vector<BenchmarkResult> benchmark_datalake_write(const std::string& language,
                                                        const std::vector<SampleBook>& books,
                                                        const std::filesystem::path& output_dir) {
    const int dataset_size = static_cast<int>(books.size());
    std::vector<BenchmarkResult> results;

    for (const auto& structure : kDatalakeStructures) {
        const auto dir = output_dir / structure;
        std::unique_ptr<Datalake> datalake;
        const auto elapsed_ms = measure_elapsed_ms(
            [&] { datalake = fresh_datalake(structure, dir); },  // untimed: an empty folder every repetition
            [&] {
                for (const auto& book : books) {
                    datalake->write(book.book_id, book.header, book.body);
                }
            });

        const auto elapsed = elapsed_rows(language, "datalake_write", structure, dataset_size, elapsed_ms);
        const auto throughput = derived_rows(elapsed, "throughput", "books_per_s",
                                             [&](double ms) { return dataset_size / (ms / 1000.0); });
        results.insert(results.end(), elapsed.begin(), elapsed.end());
        results.insert(results.end(), throughput.begin(), throughput.end());
    }
    return results;
}

}  // namespace stage1
