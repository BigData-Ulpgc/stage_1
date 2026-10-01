#include "stage1/index_query_benchmark.hpp"

#include <fstream>
#include <memory>
#include <nlohmann/json.hpp>

#include "stage1/hierarchical_index_writer.hpp"
#include "stage1/mongo_index_writer.hpp"
#include "stage1/query_engine.hpp"
#include "stage1/tokenizer.hpp"

namespace stage1 {

namespace {

using Postings = std::function<std::vector<int>(const std::string&)>;

// Parses the monolithic JSON file once, then answers every term lookup from
// the parsed structure -- the "loading" step a real query service would also
// pay once before serving many queries.
Postings load_monolithic(const std::filesystem::path& path) {
    std::ifstream file(path);
    auto document = std::make_shared<nlohmann::json>(nlohmann::json::parse(file));

    return [document](const std::string& term) -> std::vector<int> {
        const auto it = document->find(term);
        if (it == document->end()) {
            return {};
        }
        return it->get<std::vector<int>>();
    };
}

// The hierarchical layout has nothing to "load" upfront: each term is its own
// file, opened and read on demand. That per-term file access, repeated once
// per query term, is exactly this structure's real query cost.
Postings load_hierarchical(const std::filesystem::path& root) {
    return [root](const std::string& term) -> std::vector<int> {
        const std::filesystem::path path = root / hierarchical_folder_name(term) / (term + ".txt");
        std::ifstream file(path);
        if (!file) {
            return {};
        }
        std::vector<int> postings;
        int book_id = 0;
        while (file >> book_id) {
            postings.push_back(book_id);
        }
        return postings;
    };
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
        [&] { return load_monolithic(index_dir / "monolithic" / "inverted_index.json"); }, results);

    run_and_record(
        language, "hierarchical", dataset_size, queries, stopwords,
        [&] { return load_hierarchical(index_dir / "hierarchical" / "inverted_index"); }, results);

    if (mongo_is_reachable()) {
        run_and_record(
            language, "mongo", dataset_size, queries, stopwords, [&] { return mongo_postings_fetcher(); }, results);
    }

    return results;
}

}  // namespace stage1
