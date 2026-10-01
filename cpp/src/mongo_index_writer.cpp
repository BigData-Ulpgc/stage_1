#include "stage1/mongo_index_writer.hpp"

#include <bsoncxx/builder/basic/array.hpp>
#include <bsoncxx/builder/basic/document.hpp>
#include <bsoncxx/builder/basic/kvp.hpp>
#include <mongocxx/client.hpp>
#include <mongocxx/exception/exception.hpp>
#include <mongocxx/instance.hpp>
#include <mongocxx/options/index.hpp>
#include <mongocxx/options/update.hpp>
#include <mongocxx/uri.hpp>

#include <memory>
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

bool mongo_is_reachable(const std::string& uri) {
    ensure_mongo_driver_initialized();
    try {
        mongocxx::client client{mongocxx::uri{uri}};
        client["admin"].run_command(make_document(kvp("ping", 1)));
        return true;
    } catch (const mongocxx::exception&) {
        return false;
    }
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

void MongoIndexWriter::update_terms(const InvertedIndex& index, const std::vector<std::string>& changed_terms) {
    try {
        mongocxx::client client{mongocxx::uri{uri_}};
        mongocxx::collection collection = client["search_engine"]["inverted_index"];

        mongocxx::options::update upsert;
        upsert.upsert(true);
        for (const auto& term : changed_terms) {
            bsoncxx::builder::basic::array postings_array;
            for (int book_id : index.postings(term)) {
                postings_array.append(book_id);
            }
            // $set, not a full document replace: touches only this one
            // document (term)'s "postings" field, same spirit as
            // HierarchicalIndexWriter::update_terms touching only that
            // term's file. upsert(true) covers a term that is brand new.
            collection.update_one(make_document(kvp("term", term)),
                                   make_document(kvp("$set", make_document(kvp("postings", postings_array.extract())))),
                                   upsert);
        }
    } catch (const mongocxx::exception& error) {
        throw std::runtime_error(std::string("failed to update index in MongoDB: ") + error.what());
    }
}

std::function<std::vector<int>(const std::string&)> mongo_postings_fetcher(const std::string& uri) {
    ensure_mongo_driver_initialized();
    // Held by shared_ptr, not by value: mongocxx::client is move-only, and a
    // std::function's target must be copyable.
    auto client = std::make_shared<mongocxx::client>(mongocxx::uri{uri});

    return [client](const std::string& term) -> std::vector<int> {
        auto collection = (*client)["search_engine"]["inverted_index"];
        const auto doc = collection.find_one(make_document(kvp("term", term)));
        if (!doc) {
            return {};
        }
        std::vector<int> postings;
        for (const auto& element : doc->view()["postings"].get_array().value) {
            postings.push_back(element.get_int32().value);
        }
        return postings;
    };
}

}  // namespace stage1
