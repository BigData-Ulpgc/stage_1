#include "stage1/datalake_lookup_benchmark.hpp"

#include "stage1/book_based_datalake.hpp"
#include "stage1/range_based_datalake.hpp"
#include "stage1/time_based_datalake.hpp"

namespace stage1 {

namespace {

// Writes every book through `datalake` once (untimed: this experiment
// measures locate(), not write()), then times calling locate() for every
// book id, once per measure_elapsed_ms repetition, appending one
// BenchmarkResult per measured run.
void run_and_record(const std::string& language, const std::string& structure, const std::vector<SampleBook>& books,
                     Datalake& datalake, std::vector<BenchmarkResult>& results) {
    for (const auto& book : books) {
        datalake.write(book.book_id, book.header, book.body);
    }

    const auto elapsed = measure_elapsed_ms([&] {
        for (const auto& book : books) {
            datalake.locate(book.book_id);
        }
    });

    const int dataset_size = static_cast<int>(books.size());
    int repetition = 1;
    for (double ms : elapsed) {
        results.push_back(
            BenchmarkResult{language, "datalake_lookup", structure, dataset_size, repetition++, "elapsed", ms, "ms"});
    }
}

}  // namespace

std::vector<BenchmarkResult> benchmark_datalake_lookup(const std::string& language,
                                                         const std::vector<SampleBook>& books,
                                                         const std::filesystem::path& output_dir) {
    std::vector<BenchmarkResult> results;

    BookBasedDatalake book_datalake(output_dir / "book");
    run_and_record(language, "book", books, book_datalake, results);

    RangeBasedDatalake range_datalake(output_dir / "range");
    run_and_record(language, "range", books, range_datalake, results);

    SystemClock clock;
    TimeBasedDatalake time_datalake(output_dir / "time", clock);
    run_and_record(language, "time", books, time_datalake, results);

    return results;
}

}  // namespace stage1
