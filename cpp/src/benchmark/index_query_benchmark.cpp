#include "stage1/benchmark/index_query_benchmark.hpp"

#include "stage1/datamart/index/hierarchical_index_writer.hpp"
#include "stage1/datamart/index/inverted_index.hpp"
#include "stage1/datamart/index/monolithic_index_writer.hpp"

#include "stage1/datamart/index/index_readers.hpp"
#include "stage1/datamart/index/mongo_index_writer.hpp"
#include "stage1/query/query_engine.hpp"
#include "stage1/datamart/index/tokenizer.hpp"

namespace stage1 {

namespace {

using Postings = std::function<std::vector<int>(const std::string&)>;

// Where each file-based structure lives under `index_dir`: the same paths
// benchmark_index_build writes, used by both functions below.
std::filesystem::path monolithic_file(const std::filesystem::path& index_dir) {
    return index_dir / "monolithic" / "inverted_index.json";
}
std::filesystem::path hierarchical_root(const std::filesystem::path& index_dir) {
    return index_dir / "hierarchical" / "inverted_index";
}

void run_and_record(const std::string& language, const std::string& structure, int dataset_size,
                     const std::vector<std::string>& queries, const std::unordered_set<std::string>& stopwords,
                     const std::function<Postings()>& load_structure, std::vector<BenchmarkResult>& results) {
    const auto elapsed = measure_elapsed_ms([&] {
        const Postings postings = load_structure();
        for (const auto& query_text : queries) {
            query_and(postings, tokenize(query_text, stopwords));
        }
    });

    int repetition = 1;
    for (double ms : elapsed) {
        results.push_back(
            BenchmarkResult{language, "index_query", structure, dataset_size, repetition++, "elapsed", ms, "ms"});
    }
}

}  // namespace

std::vector<BenchmarkResult> benchmark_index_query(const std::string& language, int dataset_size,
                                                     const std::vector<std::string>& queries,
                                                     const std::unordered_set<std::string>& stopwords,
                                                     const std::filesystem::path& index_dir) {
    std::vector<BenchmarkResult> results;

    run_and_record(
        language, "monolithic", dataset_size, queries, stopwords,
        [&] { return monolithic_postings_fetcher(monolithic_file(index_dir)); }, results);

    run_and_record(
        language, "hierarchical", dataset_size, queries, stopwords,
        [&] { return hierarchical_postings_fetcher(hierarchical_root(index_dir)); }, results);

    if (mongo_is_reachable()) {
        run_and_record(
            language, "mongo", dataset_size, queries, stopwords, [&] { return mongo_postings_fetcher(); }, results);
    }

    return results;
}

void prepare_index_query(const std::vector<SampleBook>& books, const std::unordered_set<std::string>& stopwords,
                         const std::filesystem::path& index_dir) {
    InvertedIndex index;
    for (const auto& book : books) {
        index.add_book(book.book_id, tokenize(book.body, stopwords));
    }
    MonolithicIndexWriter(monolithic_file(index_dir)).write(index);
    HierarchicalIndexWriter(hierarchical_root(index_dir)).write(index);
    if (mongo_is_reachable()) {
        MongoIndexWriter().write(index);
    }
}

}  // namespace stage1
