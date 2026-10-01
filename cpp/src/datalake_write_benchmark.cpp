#include "stage1/datalake_write_benchmark.hpp"

#include "stage1/book_based_datalake.hpp"
#include "stage1/range_based_datalake.hpp"
#include "stage1/time_based_datalake.hpp"

namespace stage1 {

namespace {

// Times writing every book in `books` through `datalake`, once per
// measure_elapsed_ms repetition, and appends one BenchmarkResult per measured
// run. Each repetition re-writes every book id; Datalake::write already
// overwrites rather than appends (Entry 14/15/16), so repeating this is as
// safe as running it once.
void run_and_record(const std::string& language, const std::string& structure, const std::vector<SampleBook>& books,
                     Datalake& datalake, std::vector<BenchmarkResult>& results) {
    const auto elapsed = measure_elapsed_ms([&] {
        for (const auto& book : books) {
            datalake.write(book.book_id, book.header, book.body);
        }
    });

    const int dataset_size = static_cast<int>(books.size());
    int repetition = 1;
    for (double ms : elapsed) {
        results.push_back(
            BenchmarkResult{language, "datalake_write", structure, dataset_size, repetition++, "elapsed", ms, "ms"});
    }
}

}  // namespace

std::vector<BenchmarkResult> benchmark_datalake_write(const std::string& language,
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
