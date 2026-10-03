// What each CLI command actually does: wires the real components together
// and drives either run_pipeline_step (pipeline.cpp) or one benchmark
// experiment. Kept separate from main.cpp (which only parses argv and
// dispatches here) the same way the Java module separates its thin Main.java
// from SearchEngine.java, which does the equivalent wiring.
#include "stage1/cli_commands.hpp"

#include <algorithm>
#include <array>
#include <filesystem>
#include <iostream>
#include <iterator>
#include <string>
#include <vector>

#include "stage1/benchmark/benchmark.hpp"
#include "stage1/datalake/book_based_datalake.hpp"
#include "stage1/control/book_id_list.hpp"
#include "stage1/control/control_log.hpp"
#include "stage1/crawler/curl_http_client.hpp"
#include "stage1/benchmark/datalake_incremental_benchmark.hpp"
#include "stage1/benchmark/datalake_lookup_benchmark.hpp"
#include "stage1/benchmark/datalake_recovery_benchmark.hpp"
#include "stage1/benchmark/datalake_storage_benchmark.hpp"
#include "stage1/benchmark/datalake_write_benchmark.hpp"
#include "stage1/util/file_io.hpp"
#include "stage1/crawler/gutenberg_client.hpp"
#include "stage1/crawler/local_file_source.hpp"
#include "stage1/benchmark/index_build_benchmark.hpp"
#include "stage1/benchmark/index_disk_benchmark.hpp"
#include "stage1/benchmark/index_memory_benchmark.hpp"
#include "stage1/benchmark/index_query_benchmark.hpp"
#include "stage1/datamart/index/index_readers.hpp"
#include "stage1/benchmark/index_update_benchmark.hpp"
#include "stage1/datamart/index/inverted_index.hpp"
#include "stage1/benchmark/metadata_insert_benchmark.hpp"
#include "stage1/benchmark/metadata_query_benchmark.hpp"
#include "stage1/datamart/metadata/metadata_store.hpp"
#include "stage1/datamart/index/monolithic_index_writer.hpp"
#include "stage1/control/pipeline.hpp"
#include "stage1/query/query_engine.hpp"
#include "stage1/benchmark/query_list.hpp"
#include "stage1/benchmark/sample_books.hpp"
#include "stage1/datamart/index/stopwords.hpp"
#include "stage1/datamart/index/tokenizer.hpp"

namespace stage1 {

namespace {

// Fixed at compile time (see CMakeLists.txt), so the binary finds shared/ and
// its own data/ and benchmarks/ directories regardless of the current working
// directory it is launched from.
const std::filesystem::path kSharedDir = STAGE1_SHARED_DIR;
const std::filesystem::path kSampleDir = STAGE1_SAMPLE_DIR;
const std::filesystem::path kDataDir = STAGE1_DATA_DIR;
const std::filesystem::path kBenchmarksDir = STAGE1_BENCHMARKS_DIR;

// Written by `pipeline` (MonolithicIndexWriter), read back by `search`
// (monolithic_postings_fetcher): one constant so the two can never drift
// apart. Swapping pipeline's index format means swapping search's reader too.
const std::filesystem::path kIndexPath = kDataDir / "datamarts" / "inverted_index.json";

// SPEC section 10.1: the index experiments run once per size N, on the N
// books with the lowest ids (load_sample_books already returns them sorted).
constexpr std::array<std::size_t, 3> kIndexSizes = {50, 100, 200};

// One index experiment on exactly `books` (one size).
std::vector<BenchmarkResult> run_index_experiment(const std::string& experiment, const std::vector<SampleBook>& books,
                                                  const std::unordered_set<std::string>& stopwords,
                                                  const std::filesystem::path& work_dir) {
    // Every index experiment verifies the structures it measured against the
    // shared query workload, as the Java module does.
    const auto queries = load_queries(kSharedDir / "queries.txt");
    if (experiment == "index_build") {
        return benchmark_index_build("cpp", books, queries, stopwords, work_dir);
    }
    if (experiment == "index_query") {
        return benchmark_index_query("cpp", books, queries, stopwords, work_dir);
    }
    if (experiment == "index_update") {
        return benchmark_index_update("cpp", books, queries, stopwords, work_dir);
    }
    if (experiment == "index_memory") {
        return benchmark_index_memory("cpp", books, queries, stopwords, work_dir);
    }
    return benchmark_index_disk("cpp", books, queries, stopwords, work_dir);
}

// Reports what a step actually did, not just what it decided to do: a failed
// download used to be printed as "downloaded book X" (DEVLOG Entry 49).
void describe(const StepResult& step) {
    const ControlDecision& decision = step.decision;
    if (!step.completed) {
        const char* verb = decision.action == ControlAction::DownloadBook ? "download" : "index";
        std::cerr << "[pipeline] could not " << verb << " book " << decision.book_id << ": " << step.failure << "\n";
        return;
    }
    switch (decision.action) {
        case ControlAction::DownloadBook:
            std::cout << "[pipeline] downloaded book " << decision.book_id << "\n";
            break;
        case ControlAction::IndexBook:
            std::cout << "[pipeline] indexed book " << decision.book_id << "\n";
            break;
        case ControlAction::Nothing:
            std::cout << "[pipeline] nothing left to do\n";
            break;
    }
}

}  // namespace

// The datalake layout (book-based here) and the index format (monolithic
// JSON here) are each one of three SPEC-required alternatives; both sit
// behind their own interface (Datalake, IndexWriter), so swapping either for
// a benchmark run means changing these two lines, not anything in
// stage1_core.
int run_pipeline_command(int steps, bool offline) {
    const auto stopwords = load_stopwords(kSharedDir / "stopwords.txt");
    const auto candidate_ids = load_book_ids(offline ? kSampleDir / "book_ids.txt" : kSharedDir / "book_ids.txt");

    ControlLog downloaded(kDataDir / "control" / "downloaded_books.txt");
    ControlLog indexed(kDataDir / "control" / "indexed_books.txt");

    // Where the books come from is the only difference between the two modes:
    // both sources are BookSources, and nothing below this point knows which
    // one it was given.
    CurlHttpClient http_client;
    GutenbergSource gutenberg(http_client);
    LocalFileSource sample(kSampleDir / "raw");
    BookSource& source = offline ? static_cast<BookSource&>(sample) : gutenberg;
    if (offline) {
        std::cout << "[pipeline] offline: reading books from " << (kSampleDir / "raw").lexically_normal() << "\n";
    }

    BookBasedDatalake datalake(kDataDir / "datalake" / "book");
    MetadataStore metadata(kDataDir / "datamarts" / "metadata.db");
    MonolithicIndexWriter index_writer(kIndexPath);

    // Rebuilt from scratch on every run by re-reading each already-indexed
    // book's body: this stage has no reader for the on-disk index formats
    // (only writers), so this is the simplest correct way to resume with a
    // populated in-memory index. Known cost, worth revisiting once the
    // project needs to resume large runs often (see DEVLOG).
    InvertedIndex index;
    for (int book_id : candidate_ids) {
        if (!indexed.contains(book_id)) {
            continue;
        }
        if (const auto stored = metadata.find_by_id(book_id)) {
            index.add_book(book_id, tokenize(read_text_file(stored->body_path), stopwords));
        }
    }

    for (int step = 0; step < steps; ++step) {
        const auto step_result = run_pipeline_step(candidate_ids, downloaded, indexed, source, datalake, metadata,
                                                    index, index_writer, stopwords);
        describe(step_result);
        if (!step_result.completed) {
            // The book stays unmarked, so the control layer would choose it
            // again on the very next step: looping on would only repeat the
            // same failure. Stop, and let a later run retry it.
            std::cerr << "[pipeline] stopping; run `pipeline` again to retry\n";
            return 1;
        }
        if (step_result.decision.action == ControlAction::Nothing) {
            break;  // dataset fully processed: no point looping further
        }
    }

    return 0;
}

int run_search_command(const std::string& query) {
    // Same tokenizer and stopwords as indexing (SPEC section 7), otherwise a
    // query term could never match how the books' terms were stored.
    const auto stopwords = load_stopwords(kSharedDir / "stopwords.txt");
    const auto terms = tokenize(query, stopwords);
    if (terms.empty()) {
        std::cout << "[search] no searchable terms in \"" << query << "\" (empty, or only stopwords)\n";
        return 0;
    }

    if (!std::filesystem::exists(kIndexPath)) {
        std::cerr << "[search] no index found at " << kIndexPath << " -- run `pipeline <N>` first to build one.\n";
        return 1;
    }
    const auto postings = monolithic_postings_fetcher(kIndexPath);
    const auto book_ids = query_and(postings, terms);

    std::cout << book_ids.size() << " book(s) matching all of:";
    for (const auto& term : terms) {
        std::cout << " " << term;
    }
    std::cout << "\n";

    MetadataStore metadata(kDataDir / "datamarts" / "metadata.db");
    for (int book_id : book_ids) {
        const auto stored = metadata.find_by_id(book_id);
        const std::string title = stored && stored->title ? *stored->title : "(no title in metadata)";
        std::cout << "  " << book_id << "  " << title << "\n";
    }

    return 0;
}

int run_status_command() {
    const auto candidate_ids = load_book_ids(kSharedDir / "book_ids.txt");
    const ControlLog downloaded(kDataDir / "control" / "downloaded_books.txt");
    const ControlLog indexed(kDataDir / "control" / "indexed_books.txt");

    const auto downloaded_ids = downloaded.ids();
    const auto indexed_ids = indexed.ids();

    // Downloaded but not indexed yet. Both lists come out of ids() already
    // ascending, which std::set_difference requires.
    std::vector<int> pending;
    std::set_difference(downloaded_ids.begin(), downloaded_ids.end(), indexed_ids.begin(), indexed_ids.end(),
                        std::back_inserter(pending));

    std::cout << "dataset:    " << candidate_ids.size() << " book id(s) in shared/book_ids.txt\n"
              << "downloaded: " << downloaded_ids.size() << "\n"
              << "indexed:    " << indexed_ids.size() << "\n"
              << "pending:    " << pending.size();
    if (!pending.empty()) {
        std::cout << " (downloaded, not indexed yet:";
        for (int book_id : pending) {
            std::cout << " " << book_id;
        }
        std::cout << ")";
    }
    std::cout << "\n";

    return 0;
}

// Runs one SPEC section 9 experiment against books a previous `pipeline <N>`
// run already downloaded (never the network: see load_sample_books), and
// writes the result CSV to benchmarks/results/cpp_<experiment>.csv, the same
// "results get committed, work is scratch" convention the Java module uses.
int run_benchmark_command(const std::string& experiment) {
    const auto stopwords = load_stopwords(kSharedDir / "stopwords.txt");
    const auto candidate_ids = load_book_ids(kSharedDir / "book_ids.txt");

    ControlLog downloaded(kDataDir / "control" / "downloaded_books.txt");
    MetadataStore metadata(kDataDir / "datamarts" / "metadata.db");
    const auto books = load_sample_books(candidate_ids, downloaded, metadata);

    if (books.empty()) {
        std::cerr << "[benchmark] no downloaded books found under " << kDataDir
                   << " -- run `pipeline <N>` first to populate some.\n";
        return 1;
    }
    std::cout << "[benchmark] using " << books.size() << " already-downloaded book(s)\n";

    const auto work_dir = kBenchmarksDir / "work";
    std::vector<BenchmarkResult> results;

    if (experiment == "datalake_write") {
        results = benchmark_datalake_write("cpp", books, work_dir);
    } else if (experiment == "datalake_lookup") {
        results = benchmark_datalake_lookup("cpp", books, work_dir);
    } else if (experiment == "datalake_incremental") {
        results = benchmark_datalake_incremental("cpp", books, work_dir);
    } else if (experiment == "datalake_recovery") {
        results = benchmark_datalake_recovery("cpp", books, work_dir);
    } else if (experiment == "datalake_storage") {
        results = benchmark_datalake_storage("cpp", books, work_dir);
    } else if (experiment == "metadata_insert") {
        results = benchmark_metadata_insert("cpp", books, work_dir);
    } else if (experiment == "metadata_query") {
        results = benchmark_metadata_query("cpp", books, work_dir);
    } else if (experiment == "index_build" || experiment == "index_query" || experiment == "index_update" ||
               experiment == "index_memory" || experiment == "index_disk") {
        for (std::size_t n : kIndexSizes) {
            if (books.size() < n) {
                std::cerr << "[benchmark] skipping N=" << n << ": only " << books.size() << " book(s) downloaded\n";
                continue;
            }
            std::cout << "[benchmark] " << experiment << ", N=" << n << "\n";
            const std::vector<SampleBook> first_n(books.begin(), books.begin() + static_cast<std::ptrdiff_t>(n));
            const auto rows = run_index_experiment(experiment, first_n, stopwords, work_dir);
            results.insert(results.end(), rows.begin(), rows.end());
        }
        if (results.empty()) {
            std::cerr << "[benchmark] " << experiment << " needs at least " << kIndexSizes.front()
                      << " downloaded books (SPEC section 10.1) -- run `pipeline 400` first.\n";
            return 1;
        }
    } else {
        std::cerr << "[benchmark] unknown experiment: " << experiment << "\n";
        return 1;
    }

    const auto csv_path = kBenchmarksDir / "results" / ("cpp_" + experiment + ".csv");
    write_benchmark_results(csv_path, results);
    std::cout << "[benchmark] wrote " << results.size() << " rows to " << csv_path << "\n";
    return 0;
}

}  // namespace stage1
