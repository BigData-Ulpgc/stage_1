#include "stage1/benchmark/index_update_benchmark.hpp"

#include <algorithm>
#include <functional>
#include <stdexcept>

#include "stage1/benchmark/index_benchmark_support.hpp"
#include "stage1/datamart/index/hierarchical_index_writer.hpp"
#include "stage1/datamart/index/index_readers.hpp"
#include "stage1/datamart/index/mongo_index_writer.hpp"
#include "stage1/datamart/index/monolithic_index_writer.hpp"

namespace stage1 {

namespace {

using Postings = std::function<std::vector<int>(const std::string&)>;

void run_and_record(const std::string& language, const std::string& structure,
                     const std::vector<TokenizedBook>& books, std::size_t k, const std::vector<std::string>& queries,
                     const std::unordered_set<std::string>& stopwords, IndexWriter& writer,
                     const std::function<Postings()>& open, std::vector<BenchmarkResult>& results) {
    const std::vector<TokenizedBook> base(books.begin(), books.end() - static_cast<std::ptrdiff_t>(k));
    const std::vector<TokenizedBook> added(books.end() - static_cast<std::ptrdiff_t>(k), books.end());

    InvertedIndex index;
    const auto setup = [&] {
        index = build_index(base);  // the existing index a later pipeline run would find...
        writer.write(index);        // ...already persisted (write() replaces whatever was there)
    };
    const auto elapsed = measure_elapsed_ms(setup, [&] {
        for (const auto& book : added) {
            index.add_book(book.book_id, book.terms);
            writer.update_terms(index, book.terms);  // a book changes the postings of its own terms only
        }
    });
    verify_index(open(), books, queries, stopwords, structure + " index_update");  // N-k + k == building N

    const int dataset_size = static_cast<int>(books.size());
    int repetition = 1;
    for (double ms : elapsed) {
        results.push_back(
            BenchmarkResult{language, "index_update", structure, dataset_size, repetition++, "elapsed", ms, "ms"});
    }
    repetition = 1;
    for (double ms : elapsed) {
        results.push_back(BenchmarkResult{language, "index_update", structure, dataset_size, repetition++, "per_book",
                                           ms / static_cast<double>(k), "ms"});
    }
}

}  // namespace

std::vector<BenchmarkResult> benchmark_index_update(const std::string& language,
                                                      const std::vector<SampleBook>& books,
                                                      const std::vector<std::string>& queries,
                                                      const std::unordered_set<std::string>& stopwords,
                                                      const std::filesystem::path& output_dir) {
    if (books.size() < 2) {
        throw std::invalid_argument("benchmark_index_update needs at least 2 books");
    }
    const auto tokenized = tokenize_all(books, stopwords);  // before anything is timed
    const std::size_t k = std::max<std::size_t>(1, books.size() / 10);
    std::vector<BenchmarkResult> results;

    const auto monolithic_path = output_dir / "monolithic" / "inverted_index.json";
    MonolithicIndexWriter monolithic(monolithic_path);
    run_and_record(language, "monolithic", tokenized, k, queries, stopwords, monolithic,
                   [&] { return monolithic_postings_fetcher(monolithic_path); }, results);

    const auto hierarchical_root = output_dir / "hierarchical" / "inverted_index";
    HierarchicalIndexWriter hierarchical(hierarchical_root);
    run_and_record(language, "hierarchical", tokenized, k, queries, stopwords, hierarchical,
                   [&] { return hierarchical_postings_fetcher(hierarchical_root); }, results);

    if (mongo_is_reachable()) {
        MongoIndexWriter mongo;
        run_and_record(language, "mongo", tokenized, k, queries, stopwords, mongo,
                       [] { return mongo_postings_fetcher(); }, results);
    }

    return results;
}

}  // namespace stage1
