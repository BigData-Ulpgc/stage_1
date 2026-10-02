#include "stage1/control/pipeline.hpp"

#include "stage1/crawler/book_splitter.hpp"
#include "stage1/util/file_io.hpp"
#include "stage1/datamart/metadata/metadata.hpp"
#include "stage1/datamart/index/tokenizer.hpp"

namespace stage1 {

namespace {

void perform_download(int book_id, ControlLog& downloaded, BookSource& source, Datalake& datalake,
                       MetadataStore& metadata) {
    const DownloadResult download = source.fetch(book_id);
    if (!download.ok()) {
        return;  // network/HTTP failure: leave unmarked, a future run retries it
    }

    const auto split = split_book(download.text());
    if (!split) {
        return;  // missing START/END markers (SPEC section 2): book discarded
    }

    const BookLocation location = datalake.write(book_id, split->header, split->body);
    const BookMetadata book_metadata = extract_metadata(split->header);
    metadata.insert_book(book_id, book_metadata, location.body_path, location.header_path);

    downloaded.mark(book_id);  // only now: the write above fully succeeded
}

void perform_indexing(int book_id, ControlLog& indexed, MetadataStore& metadata, InvertedIndex& index,
                       IndexWriter& index_writer, const std::unordered_set<std::string>& stopwords) {
    const auto stored = metadata.find_by_id(book_id);
    if (!stored) {
        return;  // no metadata row: nothing to index yet (should not normally happen)
    }

    const std::string body = read_text_file(stored->body_path);
    index.add_book(book_id, tokenize(body, stopwords));
    index_writer.write(index);  // rewrites the whole structure, see DEVLOG

    indexed.mark(book_id);  // only now: the index has actually been persisted
}

}  // namespace

ControlDecision run_pipeline_step(const std::vector<int>& candidate_ids, ControlLog& downloaded, ControlLog& indexed,
                                   BookSource& source, Datalake& datalake, MetadataStore& metadata,
                                   InvertedIndex& index, IndexWriter& index_writer,
                                   const std::unordered_set<std::string>& stopwords) {
    const ControlDecision decision = next_control_action(candidate_ids, downloaded, indexed);

    switch (decision.action) {
        case ControlAction::DownloadBook:
            perform_download(decision.book_id, downloaded, source, datalake, metadata);
            break;
        case ControlAction::IndexBook:
            perform_indexing(decision.book_id, indexed, metadata, index, index_writer, stopwords);
            break;
        case ControlAction::Nothing:
            break;
    }

    return decision;
}

}  // namespace stage1
