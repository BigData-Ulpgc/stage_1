#include "stage1/index_update_benchmark.hpp"

#include <algorithm>
#include <stdexcept>
#include <unordered_set>

#include "stage1/hierarchical_index_writer.hpp"
#include "stage1/inverted_index.hpp"
#include "stage1/mongo_index_writer.hpp"
#include "stage1/monolithic_index_writer.hpp"
#include "stage1/tokenizer.hpp"

namespace stage1 {

namespace {

std::vector<IndexEntry> sorted_entries(const InvertedIndex& index) {
    auto entries = index.entries();
    std::sort(entries.begin(), entries.end(), [](const auto& a, const auto& b) { return a.term < b.term; });
    return entries;
}

void run_and_record(const std::string& language, const std::string& structure, const std::vector<SampleBook>& base,
                     const std::vector<SampleBook>& added, const std::unordered_set<std::string>& stopwords,
                     IndexWriter& writer, std::vector<BenchmarkResult>& results) {
    InvertedIndex index;
    const auto setup = [&] {
        index = InvertedIndex{};  // fresh, empty: the "existing index" this update starts from
        for (const auto& book : base) {
            index.add_book(book.book_id, tokenize(book.body, stopwords));
        }
        writer.write(index);  // persist the base index once, untimed
    };

    const auto elapsed = measure_elapsed_ms(setup, [&] {
        for (const auto& book : added) {
            const auto tokens = tokenize(book.body, stopwords);
            // Every term whose postings could possibly change by adding this
            // one book is exactly its own distinct term set -- nothing else
            // in the index is touched by add_book (Entry 17).
            const std::unordered_set<std::string> unique_terms(tokens.begin(), tokens.end());
            const std::vector<std::string> changed_terms(unique_terms.begin(), unique_terms.end());

            index.add_book(book.book_id, tokens);
            writer.update_terms(index, changed_terms);  // only a full rewrite if the writer has no cheaper way
        }
    });

    // The last repetition's `index` now holds base+added; it must match
    // building straight from every book, the same correctness guard already
    // used for datalake_incremental/datalake_recovery.
    InvertedIndex reference;
    for (const auto& book : base) {
        reference.add_book(book.book_id, tokenize(book.body, stopwords));
    }
    for (const auto& book : added) {
        reference.add_book(book.book_id, tokenize(book.body, stopwords));
    }
    const auto expected = sorted_entries(reference);
    const auto actual = sorted_entries(index);
    if (expected.size() != actual.size()) {
        throw std::runtime_error(structure + ": index_update left a different number of terms than expected");
    }
    for (std::size_t i = 0; i < expected.size(); ++i) {
        if (expected[i].term != actual[i].term || expected[i].postings != actual[i].postings) {
            throw std::runtime_error(structure + ": index_update produced wrong postings for term '" +
                                      expected[i].term + "'");
        }
    }

    const int dataset_size = static_cast<int>(base.size() + added.size());
    const int k = static_cast<int>(added.size());
    int repetition = 1;
    for (double ms : elapsed) {
        results.push_back(
            BenchmarkResult{language, "index_update", structure, dataset_size, repetition, "elapsed", ms, "ms"});
        results.push_back(
            BenchmarkResult{language, "index_update", structure, dataset_size, repetition, "per_book", ms / k, "ms"});
        ++repetition;
    }
}

}  // namespace

std::vector<BenchmarkResult> benchmark_index_update(const std::string& language,
                                                      const std::vector<SampleBook>& books,
                                                      const std::unordered_set<std::string>& stopwords,
                                                      const std::filesystem::path& output_dir) {
    if (books.size() < 2) {
        throw std::invalid_argument("benchmark_index_update needs at least 2 books");
    }
    const std::size_t k = std::max<std::size_t>(1, books.size() / 10);
    const std::vector<SampleBook> base(books.begin(), books.end() - static_cast<std::ptrdiff_t>(k));
    const std::vector<SampleBook> added(books.end() - static_cast<std::ptrdiff_t>(k), books.end());

    std::vector<BenchmarkResult> results;

    MonolithicIndexWriter monolithic(output_dir / "monolithic" / "inverted_index.json");
    run_and_record(language, "monolithic", base, added, stopwords, monolithic, results);

    HierarchicalIndexWriter hierarchical(output_dir / "hierarchical" / "inverted_index");
    run_and_record(language, "hierarchical", base, added, stopwords, hierarchical, results);

    if (mongo_is_reachable()) {
        MongoIndexWriter mongo;
        run_and_record(language, "mongo", base, added, stopwords, mongo, results);
    }

    return results;
}

}  // namespace stage1
