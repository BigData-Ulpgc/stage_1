#include "stage1/control/pipeline.hpp"

#include <algorithm>
#include <string>

#include "stage1/crawler/book_splitter.hpp"
#include "stage1/util/file_io.hpp"
#include "stage1/datamart/metadata/metadata.hpp"
#include "stage1/datamart/index/tokenizer.hpp"

namespace stage1 {

namespace {

// Returns why the download could not complete, or an empty string if it did.
std::string perform_download(int book_id, ControlLog& downloaded, BookSource& source, Datalake& datalake,
                              MetadataStore& metadata) {
    const DownloadResult download = source.fetch(book_id);
    if (!download.ok()) {
        return download.error();  // network/HTTP failure: leave unmarked, a future run retries it
    }

    const auto split = split_book(download.text());
    if (!split) {
        return "no START/END markers, book discarded (SPEC section 2)";
    }

    const BookLocation location = datalake.write(book_id, split->header, split->body);
    const BookMetadata book_metadata = extract_metadata(split->header);
    metadata.insert_book(book_id, book_metadata, location.body_path, location.header_path);

    downloaded.mark(book_id);  // only now: the write above fully succeeded
    return {};
}

// Returns why the indexing could not complete, or an empty string if it did.
std::string perform_indexing(int book_id, ControlLog& indexed, MetadataStore& metadata, InvertedIndex& index,
                              IndexWriter& index_writer, const std::unordered_set<std::string>& stopwords) {
    const auto stored = metadata.find_by_id(book_id);
    if (!stored) {
        return "no metadata row for it";  // should not normally happen: download stores it before marking
    }

    // The book's distinct terms: the ones whose postings change.
    std::vector<std::string> terms = tokenize(read_text_file(stored->body_path), stopwords);
    std::sort(terms.begin(), terms.end());
    terms.erase(std::unique(terms.begin(), terms.end()), terms.end());

    index.add_book(book_id, terms);
    // Only this book's terms are persisted, as the Java module's flush()
    // does: hierarchical rewrites only their files and mongo upserts only
    // their documents. Monolithic has no cheaper path and rewrites its file.
    index_writer.update_terms(index, terms);

    indexed.mark(book_id);  // only now: the index has actually been persisted
    return {};
}

}  // namespace

StepResult run_pipeline_step(const std::vector<int>& candidate_ids, ControlLog& downloaded, ControlLog& indexed,
                              BookSource& source, Datalake& datalake, MetadataStore& metadata, InvertedIndex& index,
                              IndexWriter& index_writer, const std::unordered_set<std::string>& stopwords) {
    const ControlDecision decision = next_control_action(candidate_ids, downloaded, indexed);

    std::string failure;
    switch (decision.action) {
        case ControlAction::DownloadBook:
            failure = perform_download(decision.book_id, downloaded, source, datalake, metadata);
            break;
        case ControlAction::IndexBook:
            failure = perform_indexing(decision.book_id, indexed, metadata, index, index_writer, stopwords);
            break;
        case ControlAction::Nothing:
            break;
    }

    return StepResult{decision, failure.empty(), failure};
}

}  // namespace stage1
