#include "stage1/index_query_benchmark.hpp"

#include "stage1/index_readers.hpp"
#include "stage1/mongo_index_writer.hpp"
#include "stage1/query_engine.hpp"
#include "stage1/tokenizer.hpp"

namespace stage1 {

namespace {

using Postings = std::function<std::vector<int>(const std::string&)>;

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
        [&] { return monolithic_postings_fetcher(index_dir / "monolithic" / "inverted_index.json"); }, results);

    run_and_record(
        language, "hierarchical", dataset_size, queries, stopwords,
        [&] { return hierarchical_postings_fetcher(index_dir / "hierarchical" / "inverted_index"); }, results);

    if (mongo_is_reachable()) {
        run_and_record(
            language, "mongo", dataset_size, queries, stopwords, [&] { return mongo_postings_fetcher(); }, results);
    }

    return results;
}

}  // namespace stage1
