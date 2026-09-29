#include <gtest/gtest.h>

#include <bsoncxx/builder/basic/document.hpp>
#include <bsoncxx/builder/basic/kvp.hpp>
#include <bsoncxx/json.hpp>
#include <mongocxx/client.hpp>
#include <mongocxx/exception/exception.hpp>
#include <mongocxx/instance.hpp>
#include <mongocxx/uri.hpp>

#include "stage1/inverted_index.hpp"
#include "stage1/mongo_index_writer.hpp"

using bsoncxx::builder::basic::kvp;
using bsoncxx::builder::basic::make_document;
using stage1::InvertedIndex;
using stage1::MongoIndexWriter;

namespace {

// A short serverSelectionTimeoutMS so a missing MongoDB server (no Docker
// container/no local mongod running) fails fast instead of the driver's
// default 30-second wait, matching the "skip, don't hang or fail" approach
// already used for the real Gutenberg download test.
constexpr const char* kTestUri = "mongodb://localhost:27017/?serverSelectionTimeoutMS=1000";

bool mongo_is_reachable() {
    stage1::ensure_mongo_driver_initialized();
    try {
        mongocxx::client client{mongocxx::uri{kTestUri}};
        client["admin"].run_command(make_document(kvp("ping", 1)));
        return true;
    } catch (const mongocxx::exception&) {
        return false;
    }
}

}  // namespace

TEST(MongoIndexWriter, WritesTermsAsDocumentsWithSortedPostings) {
    if (!mongo_is_reachable()) {
        GTEST_SKIP() << "no MongoDB reachable at " << kTestUri << " (start it with `docker compose up -d`)";
    }

    InvertedIndex index;
    index.add_book(3, {"car"});
    index.add_book(1, {"car", "nice"});
    MongoIndexWriter(kTestUri).write(index);

    mongocxx::client client{mongocxx::uri{kTestUri}};
    auto collection = client["search_engine"]["inverted_index"];

    auto car_doc = collection.find_one(make_document(kvp("term", "car")));
    ASSERT_TRUE(car_doc.has_value());
    auto postings = car_doc->view()["postings"].get_array().value;
    ASSERT_EQ(std::distance(postings.begin(), postings.end()), 2);
    auto it = postings.begin();
    EXPECT_EQ((*it++).get_int32().value, 1);
    EXPECT_EQ((*it).get_int32().value, 3);
}

TEST(MongoIndexWriter, WritingAgainReplacesThePreviousContents) {
    if (!mongo_is_reachable()) {
        GTEST_SKIP() << "no MongoDB reachable at " << kTestUri;
    }

    InvertedIndex first;
    first.add_book(1, {"car"});
    MongoIndexWriter(kTestUri).write(first);

    InvertedIndex second;
    second.add_book(1, {"boat"});
    MongoIndexWriter(kTestUri).write(second);

    mongocxx::client client{mongocxx::uri{kTestUri}};
    auto collection = client["search_engine"]["inverted_index"];
    EXPECT_FALSE(collection.find_one(make_document(kvp("term", "car"))).has_value());
    EXPECT_TRUE(collection.find_one(make_document(kvp("term", "boat"))).has_value());
}

TEST(MongoIndexWriter, UnreachableServerThrowsRuntimeError) {
    // No availability skip here: this test's whole point is that MongoDB is
    // NOT reachable at this address, so it must always be able to run.
    MongoIndexWriter writer("mongodb://localhost:1/?serverSelectionTimeoutMS=500");
    InvertedIndex index;
    index.add_book(1, {"car"});

    EXPECT_THROW(writer.write(index), std::runtime_error);
}
