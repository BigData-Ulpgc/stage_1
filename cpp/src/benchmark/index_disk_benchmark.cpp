#include "stage1/benchmark/index_disk_benchmark.hpp"

#include "stage1/datamart/index/hierarchical_index_writer.hpp"
#include "stage1/datamart/index/inverted_index.hpp"
#include "stage1/datamart/index/monolithic_index_writer.hpp"
#include "stage1/datamart/index/tokenizer.hpp"

namespace stage1 {

namespace {

struct DiskUsage {
    long long bytes = 0;
    long long files = 0;
};

// Sums the size of, and counts, every regular file anywhere under `dir`.
DiskUsage disk_usage(const std::filesystem::path& dir) {
    DiskUsage usage;
    if (!std::filesystem::exists(dir)) {
        return usage;
    }
    for (const auto& entry : std::filesystem::recursive_directory_iterator(dir)) {
        if (entry.is_regular_file()) {
            ++usage.files;
            usage.bytes += static_cast<long long>(entry.file_size());
        }
    }
    return usage;
}

long long file_bytes(const std::filesystem::path& path) {
    std::error_code error;
    const auto size = std::filesystem::file_size(path, error);
    return error ? 0 : static_cast<long long>(size);
}

void record(const std::string& language, const std::string& structure, int dataset_size, const DiskUsage& usage,
            long long terms, long long postings, std::vector<BenchmarkResult>& results) {
    results.push_back(BenchmarkResult{language, "index_disk", structure, dataset_size, 1, "bytes",
                                       static_cast<double>(usage.bytes), "bytes"});
    results.push_back(BenchmarkResult{language, "index_disk", structure, dataset_size, 1, "files",
                                       static_cast<double>(usage.files), "count"});
    results.push_back(
        BenchmarkResult{language, "index_disk", structure, dataset_size, 1, "terms", static_cast<double>(terms), "count"});
    results.push_back(BenchmarkResult{language, "index_disk", structure, dataset_size, 1, "postings",
                                       static_cast<double>(postings), "count"});
}

}  // namespace

std::vector<BenchmarkResult> benchmark_index_disk(const std::string& language, const std::vector<SampleBook>& books,
                                                    const std::unordered_set<std::string>& stopwords,
                                                    const std::filesystem::path& output_dir) {
    InvertedIndex index;
    for (const auto& book : books) {
        index.add_book(book.book_id, tokenize(book.body, stopwords));
    }

    // The index's logical size: identical for every structure, since they
    // all persist the exact same (term, postings) data, just physically
    // differently.
    const auto entries = index.entries();
    const long long terms = static_cast<long long>(entries.size());
    long long postings = 0;
    for (const auto& entry : entries) {
        postings += static_cast<long long>(entry.postings.size());
    }

    const int dataset_size = static_cast<int>(books.size());
    std::vector<BenchmarkResult> results;

    const auto monolithic_path = output_dir / "monolithic" / "inverted_index.json";
    MonolithicIndexWriter(monolithic_path).write(index);
    record(language, "monolithic", dataset_size, DiskUsage{file_bytes(monolithic_path), 1}, terms, postings, results);

    const auto hierarchical_dir = output_dir / "hierarchical";
    HierarchicalIndexWriter(hierarchical_dir).write(index);
    record(language, "hierarchical", dataset_size, disk_usage(hierarchical_dir), terms, postings, results);

    return results;
}

}  // namespace stage1
