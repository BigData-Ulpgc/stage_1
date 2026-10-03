#include "stage1/benchmark/index_memory_benchmark.hpp"

#if defined(__APPLE__)
#include <malloc/malloc.h>
#elif defined(__GLIBC__)
#include <malloc.h>
#else
#error "index_memory needs malloc statistics: macOS or glibc (Linux) only"
#endif

#include <algorithm>
#include <functional>

#include "stage1/datamart/index/hierarchical_index_writer.hpp"
#include "stage1/datamart/index/index_readers.hpp"
#include "stage1/datamart/index/inverted_index.hpp"
#include "stage1/datamart/index/mongo_index_writer.hpp"
#include "stage1/datamart/index/monolithic_index_writer.hpp"
#include "stage1/datamart/index/tokenizer.hpp"

namespace stage1 {

namespace {

using Postings = std::function<std::vector<int>(const std::string&)>;

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

    // Tokenized once, before any measurement (Java's tokenizeAll), keeping only
    // each book's distinct terms: all an index ever stores of a book.
    std::vector<std::vector<std::string>> book_terms;
    book_terms.reserve(books.size());
    for (const auto& book : books) {
        auto terms = tokenize(book.body, stopwords);
        std::sort(terms.begin(), terms.end());
        terms.erase(std::unique(terms.begin(), terms.end()), terms.end());
        book_terms.push_back(std::move(terms));
    }

    std::vector<BenchmarkResult> results;
    const auto measure = [&](const std::string& structure, IndexWriter& writer, const std::function<Postings()>& open) {
        double after_build = 0;
        {
            const double before = heap_in_use_bytes();
            InvertedIndex index;
            for (std::size_t i = 0; i < books.size(); ++i) {
                index.add_book(books[i].book_id, book_terms[i]);
            }
            writer.write(index);
            after_build = heap_in_use_bytes() - before;  // `index` is still alive here
        }
        const double before_open = heap_in_use_bytes();
        const Postings opened = open();
        const double after_open = heap_in_use_bytes() - before_open;  // `opened` is still alive here

        results.push_back(BenchmarkResult{language, "index_memory", structure, dataset_size, 1, "heap_after_build",
                                           after_build, "bytes"});
        results.push_back(BenchmarkResult{language, "index_memory", structure, dataset_size, 1, "heap_after_open",
                                           after_open, "bytes"});
    };

    const auto monolithic_path = output_dir / "monolithic" / "inverted_index.json";
    MonolithicIndexWriter monolithic(monolithic_path);
    measure("monolithic", monolithic, [&] { return monolithic_postings_fetcher(monolithic_path); });

    const auto hierarchical_path = output_dir / "hierarchical" / "inverted_index";
    HierarchicalIndexWriter hierarchical(hierarchical_path);
    measure("hierarchical", hierarchical, [&] { return hierarchical_postings_fetcher(hierarchical_path); });

    if (mongo_is_reachable()) {
        MongoIndexWriter mongo;
        measure("mongo", mongo, [] { return mongo_postings_fetcher(); });
    }
    return results;
}

}  // namespace stage1
