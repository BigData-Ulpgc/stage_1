#include "stage1/datalake_storage_benchmark.hpp"

#include <algorithm>
#include <unordered_map>

#include "stage1/book_based_datalake.hpp"
#include "stage1/range_based_datalake.hpp"
#include "stage1/time_based_datalake.hpp"

namespace stage1 {

namespace {

struct StorageStats {
    long long files = 0;
    long long directories = 0;
    long long max_entries_per_dir = 0;
    long long bytes = 0;
};

// Walks the whole tree under `root` (every file and directory, at every
// depth), counting as it goes. `entries_per_dir` is keyed by each entry's
// parent path, so the root's own direct children count toward it too, same
// as the Java module's own DatalakeStats ("incluida la raíz").
StorageStats compute_storage_stats(const std::filesystem::path& root) {
    StorageStats stats;
    if (!std::filesystem::exists(root)) {
        return stats;
    }

    std::unordered_map<std::string, long long> entries_per_dir;
    for (const auto& entry : std::filesystem::recursive_directory_iterator(root)) {
        ++entries_per_dir[entry.path().parent_path().string()];
        if (entry.is_directory()) {
            ++stats.directories;
        } else if (entry.is_regular_file()) {
            ++stats.files;
            stats.bytes += static_cast<long long>(entry.file_size());
        }
    }
    for (const auto& [dir, count] : entries_per_dir) {
        stats.max_entries_per_dir = std::max(stats.max_entries_per_dir, count);
    }
    return stats;
}

void record(const std::string& language, const std::string& structure, int dataset_size, const StorageStats& stats,
            std::vector<BenchmarkResult>& results) {
    results.push_back(
        BenchmarkResult{language, "datalake_storage", structure, dataset_size, 1, "files",
                        static_cast<double>(stats.files), "count"});
    results.push_back(
        BenchmarkResult{language, "datalake_storage", structure, dataset_size, 1, "directories",
                        static_cast<double>(stats.directories), "count"});
    results.push_back(BenchmarkResult{language, "datalake_storage", structure, dataset_size, 1,
                                       "max_entries_per_dir", static_cast<double>(stats.max_entries_per_dir),
                                       "count"});
    results.push_back(
        BenchmarkResult{language, "datalake_storage", structure, dataset_size, 1, "bytes",
                        static_cast<double>(stats.bytes), "bytes"});
}

}  // namespace

std::vector<BenchmarkResult> benchmark_datalake_storage(const std::string& language,
                                                          const std::vector<SampleBook>& books,
                                                          const std::filesystem::path& output_dir) {
    std::vector<BenchmarkResult> results;
    const int dataset_size = static_cast<int>(books.size());

    const auto book_dir = output_dir / "book";
    BookBasedDatalake book_datalake(book_dir);
    for (const auto& book : books) {
        book_datalake.write(book.book_id, book.header, book.body);
    }
    record(language, "book", dataset_size, compute_storage_stats(book_dir), results);

    const auto range_dir = output_dir / "range";
    RangeBasedDatalake range_datalake(range_dir);
    for (const auto& book : books) {
        range_datalake.write(book.book_id, book.header, book.body);
    }
    record(language, "range", dataset_size, compute_storage_stats(range_dir), results);

    const auto time_dir = output_dir / "time";
    SystemClock clock;
    TimeBasedDatalake time_datalake(time_dir, clock);
    for (const auto& book : books) {
        time_datalake.write(book.book_id, book.header, book.body);
    }
    record(language, "time", dataset_size, compute_storage_stats(time_dir), results);

    return results;
}

}  // namespace stage1
