// What each CLI command actually does: wires the real components together
// and drives either run_pipeline_step (pipeline.cpp) or one benchmark
// experiment. Kept separate from main.cpp (which only parses argv and
// dispatches here) the same way the Java module separates its thin Main.java
// from SearchEngine.java, which does the equivalent wiring.
#include "stage1/cli_commands.hpp"

#include <filesystem>
#include <iostream>
#include <vector>

#include "stage1/benchmark.hpp"
#include "stage1/book_based_datalake.hpp"
#include "stage1/book_id_list.hpp"
#include "stage1/control_log.hpp"
#include "stage1/curl_http_client.hpp"
#include "stage1/datalake_incremental_benchmark.hpp"
#include "stage1/datalake_lookup_benchmark.hpp"
#include "stage1/datalake_recovery_benchmark.hpp"
#include "stage1/datalake_storage_benchmark.hpp"
#include "stage1/datalake_write_benchmark.hpp"
#include "stage1/file_io.hpp"
#include "stage1/gutenberg_client.hpp"
#include "stage1/index_build_benchmark.hpp"
#include "stage1/index_disk_benchmark.hpp"
#include "stage1/index_memory_benchmark.hpp"
#include "stage1/index_query_benchmark.hpp"
#include "stage1/index_update_benchmark.hpp"
#include "stage1/inverted_index.hpp"
#include "stage1/metadata_insert_benchmark.hpp"
#include "stage1/metadata_query_benchmark.hpp"
#include "stage1/metadata_store.hpp"
#include "stage1/monolithic_index_writer.hpp"
#include "stage1/pipeline.hpp"
#include "stage1/query_list.hpp"
#include "stage1/sample_books.hpp"
#include "stage1/stopwords.hpp"
#include "stage1/tokenizer.hpp"

namespace stage1 {

namespace {

// Fixed at compile time (see CMakeLists.txt), so the binary finds shared/ and
// its own data/ and benchmarks/ directories regardless of the current working
// directory it is launched from.
const std::filesystem::path kSharedDir = STAGE1_SHARED_DIR;
const std::filesystem::path kDataDir = STAGE1_DATA_DIR;
const std::filesystem::path kBenchmarksDir = STAGE1_BENCHMARKS_DIR;

void describe(const ControlDecision& decision) {
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
int run_pipeline_command(int steps) {
    const auto stopwords = load_stopwords(kSharedDir / "stopwords.txt");
    const auto candidate_ids = load_book_ids(kSharedDir / "book_ids.txt");

    ControlLog downloaded(kDataDir / "control" / "downloaded_books.txt");
    ControlLog indexed(kDataDir / "control" / "indexed_books.txt");

    CurlHttpClient http_client;
    GutenbergSource source(http_client);
    BookBasedDatalake datalake(kDataDir / "datalake" / "book");
    MetadataStore metadata(kDataDir / "datamarts" / "metadata.db");
    MonolithicIndexWriter index_writer(kDataDir / "datamarts" / "inverted_index.json");

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
        const auto decision = run_pipeline_step(candidate_ids, downloaded, indexed, source, datalake, metadata,
                                                 index, index_writer, stopwords);
        describe(decision);
        if (decision.action == ControlAction::Nothing) {
            break;  // dataset fully processed: no point looping further
        }
    }

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
    } else if (experiment == "index_build") {
        results = benchmark_index_build("cpp", books, stopwords, work_dir);
    } else if (experiment == "index_query") {
        // (Re)builds the structures first, untimed, so index_query always
        // measures against whatever `books` currently holds, regardless of
        // whether `index_build` happened to run earlier in this process.
        benchmark_index_build("cpp", books, stopwords, work_dir);
        const auto queries = load_queries(kSharedDir / "queries.txt");
        results = benchmark_index_query("cpp", static_cast<int>(books.size()), queries, stopwords, work_dir);
    } else if (experiment == "index_update") {
        results = benchmark_index_update("cpp", books, stopwords, work_dir);
    } else if (experiment == "index_memory") {
        results = benchmark_index_memory("cpp", books, stopwords, work_dir);
    } else if (experiment == "index_disk") {
        results = benchmark_index_disk("cpp", books, stopwords, work_dir);
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
