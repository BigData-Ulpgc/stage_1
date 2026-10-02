#include "stage1/benchmark/index_memory_benchmark.hpp"

#include <sys/resource.h>

#include <algorithm>
#include <fstream>
#include <stdexcept>

#include <nlohmann/json.hpp>

#include "stage1/datamart/index/inverted_index.hpp"
#include "stage1/datamart/index/monolithic_index_writer.hpp"
#include "stage1/datamart/index/tokenizer.hpp"

namespace stage1 {

namespace {

// The process's peak resident set size so far, in bytes. getrusage's
// ru_maxrss unit differs by platform: bytes on macOS (Darwin), kilobytes on
// Linux -- a real, documented portability gotcha, not an oversight.
long peak_rss_bytes() {
    struct rusage usage{};
    getrusage(RUSAGE_SELF, &usage);
#if defined(__APPLE__)
    return static_cast<long>(usage.ru_maxrss);
#else
    return static_cast<long>(usage.ru_maxrss) * 1024;
#endif
}

// Never negative: a step that did not push the peak any higher than it
// already was (because an earlier, larger step set it) reports 0, not a
// meaningless negative number.
double rss_delta_bytes(long before, long after) { return static_cast<double>(std::max<long>(0, after - before)); }

}  // namespace

std::vector<BenchmarkResult> benchmark_index_memory(const std::string& language,
                                                      const std::vector<SampleBook>& books,
                                                      const std::unordered_set<std::string>& stopwords,
                                                      const std::filesystem::path& output_dir) {
    const int dataset_size = static_cast<int>(books.size());
    std::vector<BenchmarkResult> results;

    const long before_index = peak_rss_bytes();
    InvertedIndex index;
    for (const auto& book : books) {
        index.add_book(book.book_id, tokenize(book.body, stopwords));
    }
    const long after_index = peak_rss_bytes();
    results.push_back(BenchmarkResult{language, "index_memory", "in_memory_index", dataset_size, 1, "rss_delta",
                                       rss_delta_bytes(before_index, after_index), "bytes"});

    const auto monolithic_path = output_dir / "monolithic" / "inverted_index.json";
    MonolithicIndexWriter(monolithic_path).write(index);

    const long before_load = peak_rss_bytes();
    std::ifstream file(monolithic_path);
    if (!file) {
        throw std::runtime_error("index_memory: could not reopen the monolithic file it just wrote");
    }
    const auto document = nlohmann::json::parse(file);
    const long after_load = peak_rss_bytes();
    if (!document.is_object()) {
        throw std::runtime_error("index_memory: the monolithic file did not parse back as a JSON object");
    }
    results.push_back(BenchmarkResult{language, "index_memory", "monolithic", dataset_size, 1, "rss_delta",
                                       rss_delta_bytes(before_load, after_load), "bytes"});

    return results;
}

}  // namespace stage1
