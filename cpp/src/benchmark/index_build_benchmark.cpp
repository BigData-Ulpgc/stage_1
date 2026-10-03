#include "stage1/benchmark/index_build_benchmark.hpp"

#include <filesystem>
#include <functional>

#include "stage1/datamart/index/hierarchical_index_writer.hpp"
#include "stage1/datamart/index/inverted_index.hpp"
#include "stage1/datamart/index/mongo_index_writer.hpp"
#include "stage1/datamart/index/monolithic_index_writer.hpp"
#include "stage1/datamart/index/tokenizer.hpp"

namespace stage1 {

namespace {

// Times building a fresh index from `books` and writing it through `writer`,
// once per measure_elapsed_ms repetition, and appends one BenchmarkResult per
// measured run to `results`. Each repetition is fully self-contained (its own
// fresh InvertedIndex), so no state leaks between warmup and measured runs,
// or between repetitions. `reset` runs before each repetition, untimed.
void run_and_record(const std::string& language, const std::string& structure, const std::vector<SampleBook>& books,
                     const std::unordered_set<std::string>& stopwords, IndexWriter& writer,
                     const std::function<void()>& reset, std::vector<BenchmarkResult>& results) {
    const auto elapsed = measure_elapsed_ms(reset, [&] {
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
    run_and_record(language, "monolithic", books, stopwords, monolithic, [] {}, results);

    // write() empties its folder first, so without this reset every timed
    // repetition would also delete the previous repetition's files: 22.5 s
    // for the 129,356 files of the 200-book index (DEVLOG Entry 50), which
    // would turn this into a measurement of deleting, not building. Emptying
    // the folder untimed instead is the same "setup outside the measurement"
    // rule the Java module follows.
    const auto hierarchical_root = output_dir / "hierarchical" / "inverted_index";
    HierarchicalIndexWriter hierarchical(hierarchical_root);
    run_and_record(language, "hierarchical", books, stopwords, hierarchical,
                   [&] { std::filesystem::remove_all(hierarchical_root); }, results);

    if (mongo_is_reachable()) {
        MongoIndexWriter mongo;
        run_and_record(language, "mongo", books, stopwords, mongo, [] {}, results);
    }
    // Mongo unreachable: silently skipped, not failed -- a benchmark run
    // without Docker/mongod available should still report the other two
    // structures instead of aborting the whole experiment.

    return results;
}

}  // namespace stage1
