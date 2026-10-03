#include "stage1/benchmark/index_memory_benchmark.hpp"

#if defined(__APPLE__)
#include <malloc/malloc.h>
#elif defined(__GLIBC__)
#include <malloc.h>
#else
#error "index_memory needs malloc statistics: macOS or glibc (Linux) only"
#endif

#include <fstream>
#include <stdexcept>

#include <nlohmann/json.hpp>

#include "stage1/datamart/index/inverted_index.hpp"
#include "stage1/datamart/index/monolithic_index_writer.hpp"
#include "stage1/datamart/index/tokenizer.hpp"

namespace stage1 {

namespace {

// Bytes currently allocated through malloc (and so through new) and not yet
// freed, across the whole process.
double heap_in_use_bytes() {
#if defined(__APPLE__)
    malloc_statistics_t stats{};
    malloc_zone_statistics(nullptr, &stats);  // nullptr: all zones
    return static_cast<double>(stats.size_in_use);
#else
    const struct mallinfo2 info = mallinfo2();  // glibc 2.33+
    return static_cast<double>(info.uordblks + info.hblkhd);  // heap chunks + large mmap'd blocks
#endif
}

}  // namespace

std::vector<BenchmarkResult> benchmark_index_memory(const std::string& language,
                                                      const std::vector<SampleBook>& books,
                                                      const std::unordered_set<std::string>& stopwords,
                                                      const std::filesystem::path& output_dir) {
    const int dataset_size = static_cast<int>(books.size());
    std::vector<BenchmarkResult> results;

    const double before_index = heap_in_use_bytes();
    InvertedIndex index;
    for (const auto& book : books) {
        index.add_book(book.book_id, tokenize(book.body, stopwords));
    }
    const double after_index = heap_in_use_bytes();  // `index` is still alive here
    results.push_back(BenchmarkResult{language, "index_memory", "in_memory_index", dataset_size, 1, "heap_delta",
                                       after_index - before_index, "bytes"});

    const auto monolithic_path = output_dir / "monolithic" / "inverted_index.json";
    MonolithicIndexWriter(monolithic_path).write(index);

    const double before_load = heap_in_use_bytes();
    std::ifstream file(monolithic_path);
    if (!file) {
        throw std::runtime_error("index_memory: could not reopen the monolithic file it just wrote");
    }
    const auto document = nlohmann::json::parse(file);
    const double after_load = heap_in_use_bytes();  // `document` is still alive here
    if (!document.is_object()) {
        throw std::runtime_error("index_memory: the monolithic file did not parse back as a JSON object");
    }
    results.push_back(BenchmarkResult{language, "index_memory", "monolithic", dataset_size, 1, "heap_delta",
                                       after_load - before_load, "bytes"});

    return results;
}

}  // namespace stage1
