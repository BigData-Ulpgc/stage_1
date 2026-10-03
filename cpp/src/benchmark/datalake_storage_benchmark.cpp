#include "stage1/benchmark/datalake_storage_benchmark.hpp"

#include <algorithm>
#include <unordered_map>

#include "stage1/benchmark/datalake_benchmark_support.hpp"

namespace stage1 {

namespace {

struct StorageStats {
    long long files = 0;
    long long directories = 0;
    long long max_entries_per_dir = 0;
    long long bytes = 0;
    long long allocated_bytes = 0;
};

// Walks the whole tree under `root` (every file and directory, at every
// depth), counting as it goes. `entries_per_dir` is keyed by each entry's
// parent path, so the root's own direct children count toward it too, same
// as the Java module's own DatalakeStats ("root included").
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
    results.push_back(
        BenchmarkResult{language, "datalake_storage", structure, dataset_size, 1, "allocated_bytes",
                        static_cast<double>(stats.allocated_bytes), "bytes"});
}

}  // namespace

std::vector<BenchmarkResult> benchmark_datalake_storage(const std::string& language,
                                                          const std::vector<SampleBook>& books,
                                                          const std::filesystem::path& output_dir) {
    const int dataset_size = static_cast<int>(books.size());
    std::vector<BenchmarkResult> results;

    for (const auto& structure : kDatalakeStructures) {
        const auto dir = output_dir / structure;
        const auto datalake = fresh_datalake(structure, dir);  // empty first: no leftovers from earlier runs
        for (const auto& book : books) {
            datalake->write(book.book_id, book.header, book.body);
        }
        StorageStats stats = compute_storage_stats(dir);
        stats.allocated_bytes = allocated_bytes(dir);
        record(language, structure, dataset_size, stats, results);
    }
    return results;
}

}  // namespace stage1
