#include "stage1/mongo_index_writer.hpp"

#include <bsoncxx/builder/basic/array.hpp>
#include <bsoncxx/builder/basic/document.hpp>
#include <bsoncxx/builder/basic/kvp.hpp>
#include <mongocxx/client.hpp>
#include <mongocxx/exception/exception.hpp>
#include <mongocxx/instance.hpp>
#include <mongocxx/options/index.hpp>
#include <mongocxx/uri.hpp>

#include <stdexcept>
#include <vector>

namespace stage1 {

namespace {

using bsoncxx::builder::basic::kvp;
using bsoncxx::builder::basic::make_document;

}  // namespace

// Defined here, called from everywhere that touches mongocxx (see the
// declaration in mongo_index_writer.hpp for why there must be exactly one
// caller-visible entry point for this).
void ensure_mongo_driver_initialized() {
    static mongocxx::instance instance{};
}

MongoIndexWriter::MongoIndexWriter(std::string uri) : uri_(std::move(uri)) { ensure_mongo_driver_initialized(); }

void MongoIndexWriter::write(const InvertedIndex& index) {
    try {
        mongocxx::client client{mongocxx::uri{uri_}};
        mongocxx::collection collection = client["search_engine"]["inverted_index"];

        // write() replaces the collection's contents wholesale, the same way
        // MonolithicIndexWriter overwrites its file and HierarchicalIndexWriter
        // overwrites each term's file: every writer's write() means "make the
        // structure match this index", not "append to whatever was there".
        collection.delete_many(make_document());
        collection.create_index(make_document(kvp("term", 1)), mongocxx::options::index{}.unique(true));

        const auto entries = index.entries();
        if (entries.empty()) {
            return;
        }

        std::vector<bsoncxx::document::value> documents;
        documents.reserve(entries.size());
        for (const auto& entry : entries) {
            bsoncxx::builder::basic::array postings_array;
            for (int book_id : entry.postings) {
                postings_array.append(book_id);
            }
            documents.push_back(make_document(kvp("term", entry.term), kvp("postings", postings_array.extract())));
        }

        collection.insert_many(documents);
    } catch (const mongocxx::exception& error) {
        throw std::runtime_error(std::string("failed to write index to MongoDB: ") + error.what());
    }
}

}  // namespace stage1
