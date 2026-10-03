#include "stage1/benchmark/index_build_benchmark.hpp"

#include <algorithm>
#include <filesystem>
#include <functional>

#include "stage1/benchmark/index_benchmark_support.hpp"
#include "stage1/datamart/index/hierarchical_index_writer.hpp"
#include "stage1/datamart/index/index_readers.hpp"
#include "stage1/datamart/index/mongo_index_writer.hpp"
#include "stage1/datamart/index/monolithic_index_writer.hpp"

namespace stage1 {

namespace {

using Postings = std::function<std::vector<int>(const std::string&)>;

// One structure: `reset` empties its storage before every repetition
// (untimed); the timed part builds the index and writes it; `open` then reads
// the written structure back for verify_index.
void run_and_record(const std::string& language, const std::string& structure,
                     const std::vector<TokenizedBook>& books, const std::vector<std::string>& queries,
                     const std::unordered_set<std::string>& stopwords, IndexWriter& writer,
                     const std::function<void()>& reset, const std::function<Postings()>& open,
                     std::vector<BenchmarkResult>& results) {
    const auto elapsed = measure_elapsed_ms(reset, [&] {
        const InvertedIndex index = build_index(books);
        writer.write(index);
    });
    verify_index(open(), books, queries, stopwords, structure + " index_build");

    const int dataset_size = static_cast<int>(books.size());
    int repetition = 1;
    for (double ms : elapsed) {
        results.push_back(
            BenchmarkResult{language, "index_build", structure, dataset_size, repetition++, "elapsed", ms, "ms"});
    }
    repetition = 1;
    for (double ms : elapsed) {
        const double books_per_s = dataset_size / (std::max(ms, 0.001) / 1000.0);  // as Java: never divide by 0
        results.push_back(BenchmarkResult{language, "index_build", structure, dataset_size, repetition++, "throughput",
                                           books_per_s, "books_per_s"});
    }
}

}  // namespace

std::vector<BenchmarkResult> benchmark_index_build(const std::string& language, const std::vector<SampleBook>& books,
                                                     const std::vector<std::string>& queries,
                                                     const std::unordered_set<std::string>& stopwords,
                                                     const std::filesystem::path& output_dir) {
    const auto tokenized = tokenize_all(books, stopwords);  // before anything is timed
    std::vector<BenchmarkResult> results;

    const auto monolithic_path = output_dir / "monolithic" / "inverted_index.json";
    MonolithicIndexWriter monolithic(monolithic_path);
    run_and_record(
        language, "monolithic", tokenized, queries, stopwords, monolithic,
        [&] { std::filesystem::remove(monolithic_path); },
        [&] { return monolithic_postings_fetcher(monolithic_path); }, results);

    // The hierarchical folder is emptied in the untimed reset: write() would
    // otherwise first delete the previous repetition's files inside the
    // measured time (22.5 s for the 129,356 files of the 200-book index,
    // DEVLOG Entry 50).
    const auto hierarchical_root = output_dir / "hierarchical" / "inverted_index";
    HierarchicalIndexWriter hierarchical(hierarchical_root);
    run_and_record(
        language, "hierarchical", tokenized, queries, stopwords, hierarchical,
        [&] { std::filesystem::remove_all(hierarchical_root); },
        [&] { return hierarchical_postings_fetcher(hierarchical_root); }, results);

    if (mongo_is_reachable()) {
        // The benchmarks' own database, never the real index's (as in Java);
        // the collection is dropped in the untimed reset, as Java's setup does.
        MongoIndexWriter mongo(kMongoUri, kMongoBenchDatabase);
        run_and_record(
            language, "mongo", tokenized, queries, stopwords, mongo, [&] { mongo.clear(); },
            [] { return mongo_postings_fetcher(kMongoUri, kMongoBenchDatabase); }, results);
    }
    // Mongo unreachable: skipped, not failed, so a run without Docker still
    // reports the other two structures.

    return results;
}

}  // namespace stage1
