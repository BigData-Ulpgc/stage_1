#pragma once

#include <string>

#include "stage1/index_writer.hpp"

namespace stage1 {

// The mongocxx driver requires exactly one mongocxx::instance to be alive for
// the whole process, constructed before any other mongocxx object is used;
// creating a second one throws. This function guarantees that through a single
// function-local static, defined once in mongo_index_writer.cpp. Every piece of
// code in this project that touches mongocxx -- MongoIndexWriter itself, and
// tests that read the collection back directly -- must call this (it is safe
// and cheap to call more than once) instead of each creating its own
// mongocxx::instance, which is exactly what would violate the "exactly one"
// rule the moment two of them ran in the same process.
void ensure_mongo_driver_initialized();

// Writes the whole index into MongoDB (shared/SPEC.md section 6): database
// "search_engine", collection "inverted_index", one document per term,
// {"term": "...", "postings": [id1, id2, ...]}, with a unique index on `term`.
//
// The server can run natively or, as this group chose, inside Docker (see the
// repository's docker-compose.yml) -- from here it is just a connection URI,
// same reasoning as HttpClient not caring whether libcurl talks to a real or a
// containerized service.
//
// Throws std::runtime_error if the server is unreachable or a write fails.
class MongoIndexWriter : public IndexWriter {
public:
    explicit MongoIndexWriter(std::string uri = "mongodb://localhost:27017");

    void write(const InvertedIndex& index) override;

private:
    std::string uri_;
};

}  // namespace stage1
