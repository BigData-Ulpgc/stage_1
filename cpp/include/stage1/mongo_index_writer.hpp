#pragma once

#include <functional>
#include <string>
#include <vector>

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

// True if a MongoDB server responds to a ping at `uri` within its (short,
// caller-controlled) serverSelectionTimeoutMS. Lets code skip Mongo-dependent
// work gracefully -- e.g. a benchmark run without Docker/mongod available --
// instead of waiting out the driver's default ~30s timeout or crashing.
bool mongo_is_reachable(const std::string& uri = "mongodb://localhost:27017/?serverSelectionTimeoutMS=1000");

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
    void update_terms(const InvertedIndex& index, const std::vector<std::string>& changed_terms) override;

private:
    std::string uri_;
};

// Returns a postings-fetcher backed by a live MongoDB connection at `uri`:
// one query per term against the "mongo" structure MongoIndexWriter wrote.
// Used by benchmarks that need to measure querying each on-disk/database
// structure directly, not through the in-memory InvertedIndex.
std::function<std::vector<int>(const std::string&)> mongo_postings_fetcher(
    const std::string& uri = "mongodb://localhost:27017");

}  // namespace stage1
