#include "stage1/index_build_benchmark.hpp"

#include "stage1/hierarchical_index_writer.hpp"
#include "stage1/inverted_index.hpp"
#include "stage1/mongo_index_writer.hpp"
#include "stage1/monolithic_index_writer.hpp"
#include "stage1/tokenizer.hpp"

namespace stage1 {

namespace {

// Times building a fresh index from `books` and writing it through `writer`,
// once per measure_elapsed_ms repetition, and appends one BenchmarkResult per
// measured run to `results`. Each repetition is fully self-contained (its own
// fresh InvertedIndex), so no state leaks between warmup and measured runs,
// or between repetitions.
void run_and_record(const std::string& language, const std::string& structure, const std::vector<SampleBook>& books,
                     const std::unordered_set<std::string>& stopwords, IndexWriter& writer,
                     std::vector<BenchmarkResult>& results) {
    const auto elapsed = measure_elapsed_ms([&] {
        InvertedIndex index;
        for (const auto& book : books) {
            index.add_book(book.book_id, tokenize(book.body, stopwords));
        }
        writer.write(index);
    });

    const int dataset_size = static_cast<int>(books.size());
    int repetition = 1;
    for (double ms : elapsed) {
        results.push_back(
            BenchmarkResult{language, "index_build", structure, dataset_size, repetition++, "elapsed", ms, "ms"});
    }
}

}  // namespace

std::vector<BenchmarkResult> benchmark_index_build(const std::string& language, const std::vector<SampleBook>& books,
                                                     const std::unordered_set<std::string>& stopwords,
                                                     const std::filesystem::path& output_dir) {
    std::vector<BenchmarkResult> results;

    MonolithicIndexWriter monolithic(output_dir / "monolithic" / "inverted_index.json");
    run_and_record(language, "monolithic", books, stopwords, monolithic, results);

    HierarchicalIndexWriter hierarchical(output_dir / "hierarchical" / "inverted_index");
    run_and_record(language, "hierarchical", books, stopwords, hierarchical, results);

    if (mongo_is_reachable()) {
        MongoIndexWriter mongo;
        run_and_record(language, "mongo", books, stopwords, mongo, results);
    }
    // Mongo unreachable: silently skipped, not failed -- a benchmark run
    // without Docker/mongod available should still report the other two
    // structures instead of aborting the whole experiment.

    return results;
}

}  // namespace stage1
