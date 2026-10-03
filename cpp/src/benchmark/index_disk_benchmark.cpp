#include "stage1/benchmark/index_disk_benchmark.hpp"

#include <filesystem>
#include <unordered_set>

#include "stage1/benchmark/index_benchmark_support.hpp"
#include "stage1/datamart/index/hierarchical_index_writer.hpp"
#include "stage1/datamart/index/index_readers.hpp"
#include "stage1/datamart/index/monolithic_index_writer.hpp"

namespace stage1 {

namespace {

struct FileUsage {
    long long bytes = 0;
    long long files = 0;
};

// Sums the size of, and counts, every regular file anywhere under `dir`.
FileUsage file_usage(const std::filesystem::path& dir) {
    FileUsage usage;
    for (const auto& entry : std::filesystem::recursive_directory_iterator(dir)) {
        if (entry.is_regular_file()) {
            ++usage.files;
            usage.bytes += static_cast<long long>(entry.file_size());
        }
    }
    return usage;
}

void record(const std::string& language, const std::string& structure, int dataset_size,
            const std::filesystem::path& dir, long long terms, long long postings,
            std::vector<BenchmarkResult>& results) {
    const FileUsage usage = file_usage(dir);
    const auto row = [&](const char* metric, long long value, const char* unit) {
        results.push_back(BenchmarkResult{language, "index_disk", structure, dataset_size, 1, metric,
                                           static_cast<double>(value), unit});
    };
    row("bytes", usage.bytes, "bytes");  // Java's order: bytes, files, allocated_bytes, terms, postings
    row("files", usage.files, "count");
    row("allocated_bytes", allocated_bytes(dir), "bytes");
    row("terms", terms, "count");
    row("postings", postings, "count");
}

}  // namespace

std::vector<BenchmarkResult> benchmark_index_disk(const std::string& language, const std::vector<SampleBook>& books,
                                                    const std::vector<std::string>& queries,
                                                    const std::unordered_set<std::string>& stopwords,
                                                    const std::filesystem::path& output_dir) {
    const auto tokenized = tokenize_all(books, stopwords);
    const InvertedIndex index = build_index(tokenized);

    // The index's logical size, as Java computes it: distinct terms across all
    // books, and the sum of each book's distinct terms. The same for every
    // structure, since they all persist the same (term, postings) data.
    std::unordered_set<std::string> distinct;
    long long postings = 0;
    for (const auto& book : tokenized) {
        distinct.insert(book.terms.begin(), book.terms.end());
        postings += static_cast<long long>(book.terms.size());
    }
    const auto terms = static_cast<long long>(distinct.size());
    const int dataset_size = static_cast<int>(books.size());
    std::vector<BenchmarkResult> results;

    const auto monolithic_dir = output_dir / "monolithic";
    std::filesystem::remove_all(monolithic_dir);  // only this structure's file must be measured
    const auto monolithic_path = monolithic_dir / "inverted_index.json";
    MonolithicIndexWriter(monolithic_path).write(index);
    verify_index(monolithic_postings_fetcher(monolithic_path), tokenized, queries, stopwords, "monolithic index_disk");
    record(language, "monolithic", dataset_size, monolithic_dir, terms, postings, results);

    const auto hierarchical_root = output_dir / "hierarchical" / "inverted_index";
    HierarchicalIndexWriter(hierarchical_root).write(index);  // write() empties the folder first
    verify_index(hierarchical_postings_fetcher(hierarchical_root), tokenized, queries, stopwords,
                 "hierarchical index_disk");
    record(language, "hierarchical", dataset_size, hierarchical_root, terms, postings, results);

    return results;
}

}  // namespace stage1
