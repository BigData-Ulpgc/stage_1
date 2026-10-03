#include "stage1/benchmark/index_query_benchmark.hpp"

#include <cstddef>
#include <functional>
#include <stdexcept>

#include "stage1/benchmark/index_benchmark_support.hpp"
#include "stage1/datamart/index/hierarchical_index_writer.hpp"
#include "stage1/datamart/index/index_readers.hpp"
#include "stage1/datamart/index/inverted_index.hpp"
#include "stage1/datamart/index/mongo_index_writer.hpp"
#include "stage1/datamart/index/monolithic_index_writer.hpp"
#include "stage1/datamart/index/tokenizer.hpp"
#include "stage1/query/query_engine.hpp"

namespace stage1 {

namespace {

using Postings = std::function<std::vector<int>(const std::string&)>;

void run_and_record(const std::string& language, const std::string& structure, int dataset_size,
                     const std::vector<std::string>& queries, const std::unordered_set<std::string>& stopwords,
                     const Postings& postings, int query_rounds, std::vector<BenchmarkResult>& results) {
    // Every answer is added up and checked afterwards: no query can be
    // optimized away, and each repetition must have found the same books.
    std::size_t found = 0;
    std::size_t batches = 0;
    const auto elapsed = measure_elapsed_ms([&] {
        ++batches;
        for (int round = 0; round < query_rounds; ++round) {
            for (const auto& query_text : queries) {
                found += query_and(postings, tokenize(query_text, stopwords)).size();
            }
        }
    });
    if (found % batches != 0) {
        throw std::runtime_error("index_query: " + structure + " found different results across repetitions");
    }

    const double total_queries = static_cast<double>(query_rounds) * static_cast<double>(queries.size());
    int repetition = 1;
    for (double ms : elapsed) {
        results.push_back(
            BenchmarkResult{language, "index_query", structure, dataset_size, repetition++, "elapsed", ms, "ms"});
    }
    repetition = 1;
    for (double ms : elapsed) {
        results.push_back(BenchmarkResult{language, "index_query", structure, dataset_size, repetition++, "per_query",
                                           ms * 1000.0 / total_queries, "us"});
    }
}

}  // namespace

std::vector<BenchmarkResult> benchmark_index_query(const std::string& language, const std::vector<SampleBook>& books,
                                                     const std::vector<std::string>& queries,
                                                     const std::unordered_set<std::string>& stopwords,
                                                     const std::filesystem::path& index_dir, int query_rounds) {
    const int dataset_size = static_cast<int>(books.size());

    // Untimed: tokenize and build once, write every structure.
    const auto tokenized = tokenize_all(books, stopwords);
    const InvertedIndex index = build_index(tokenized);
    const auto monolithic_path = index_dir / "monolithic" / "inverted_index.json";
    const auto hierarchical_path = index_dir / "hierarchical" / "inverted_index";
    MonolithicIndexWriter(monolithic_path).write(index);
    HierarchicalIndexWriter(hierarchical_path).write(index);

    std::vector<BenchmarkResult> results;
    const auto measure = [&](const std::string& structure, const Postings& opened) {
        verify_index(opened, tokenized, queries, stopwords, structure + " index_query");
        run_and_record(language, structure, dataset_size, queries, stopwords, opened, query_rounds, results);
    };
    measure("monolithic", monolithic_postings_fetcher(monolithic_path));  // opened once, outside the timing
    measure("hierarchical", hierarchical_postings_fetcher(hierarchical_path));
    if (mongo_is_reachable()) {
        MongoIndexWriter(kMongoUri, kMongoBenchDatabase).write(index);  // never the real index's database
        measure("mongo", mongo_postings_fetcher(kMongoUri, kMongoBenchDatabase));
    }
    return results;
}

}  // namespace stage1
