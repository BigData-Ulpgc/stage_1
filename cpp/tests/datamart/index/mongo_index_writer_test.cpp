#include <gtest/gtest.h>

#include <bsoncxx/builder/basic/document.hpp>
#include <bsoncxx/builder/basic/kvp.hpp>
#include <bsoncxx/json.hpp>
#include <mongocxx/client.hpp>
#include <mongocxx/exception/exception.hpp>
#include <mongocxx/instance.hpp>
#include <mongocxx/uri.hpp>

#include <string>
#include <vector>

#include "stage1/datamart/index/inverted_index.hpp"
#include "stage1/datamart/index/mongo_index_writer.hpp"

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

// A database of the tests' own: they must never touch the real index
// ("search_engine") nor the benchmarks' ("search_engine_bench").
constexpr const char* kTestDb = "search_engine_test";

}  // namespace

TEST(MongoIndexWriter, WritesTermsAsDocumentsWithSortedPostings) {
    if (!stage1::mongo_is_reachable(kTestUri)) {
        GTEST_SKIP() << "no MongoDB reachable at " << kTestUri << " (start it with `docker compose up -d`)";
    }

    InvertedIndex index;
    index.add_book(3, {"car"});
    index.add_book(1, {"car", "nice"});
    MongoIndexWriter(kTestUri, kTestDb).write(index);

    mongocxx::client client{mongocxx::uri{kTestUri}};
    auto collection = client[kTestDb]["inverted_index"];

    auto car_doc = collection.find_one(make_document(kvp("term", "car")));
    ASSERT_TRUE(car_doc.has_value());
    auto postings = car_doc->view()["postings"].get_array().value;
    ASSERT_EQ(std::distance(postings.begin(), postings.end()), 2);
    auto it = postings.begin();
    EXPECT_EQ((*it++).get_int32().value, 1);
    EXPECT_EQ((*it).get_int32().value, 3);
}

TEST(MongoIndexWriter, WritingAgainReplacesThePreviousContents) {
    if (!stage1::mongo_is_reachable(kTestUri)) {
        GTEST_SKIP() << "no MongoDB reachable at " << kTestUri;
    }

    InvertedIndex first;
    first.add_book(1, {"car"});
    MongoIndexWriter(kTestUri, kTestDb).write(first);

    InvertedIndex second;
    second.add_book(1, {"boat"});
    MongoIndexWriter(kTestUri, kTestDb).write(second);

    mongocxx::client client{mongocxx::uri{kTestUri}};
    auto collection = client[kTestDb]["inverted_index"];
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

TEST(MongoIsReachable, ReturnsFalseForAnUnreachableAddressWithoutThrowing) {
    EXPECT_FALSE(stage1::mongo_is_reachable("mongodb://localhost:1/?serverSelectionTimeoutMS=500"));
}

TEST(MongoIndexWriter, UpdateTermsOnlyTouchesTheGivenTermsDocuments) {
    if (!stage1::mongo_is_reachable(kTestUri)) {
        GTEST_SKIP() << "no MongoDB reachable at " << kTestUri;
    }

    InvertedIndex index;
    index.add_book(1, {"car", "boat"});
    MongoIndexWriter writer(kTestUri, kTestDb);
    writer.write(index);

    index.add_book(2, {"car", "dragon"});
    writer.update_terms(index, {"car", "dragon"});

    mongocxx::client client{mongocxx::uri{kTestUri}};
    auto collection = client[kTestDb]["inverted_index"];
    EXPECT_TRUE(collection.find_one(make_document(kvp("term", "car"))).has_value());
    EXPECT_TRUE(collection.find_one(make_document(kvp("term", "dragon"))).has_value());
    // "boat" was never in changed_terms: write() already put it there, untouched since.
    EXPECT_TRUE(collection.find_one(make_document(kvp("term", "boat"))).has_value());
}

TEST(MongoIndexWriter, UpdateTermsStoresEachGivenTermsCurrentPostings) {
    if (!stage1::mongo_is_reachable(kTestUri)) {
        GTEST_SKIP() << "no MongoDB reachable at " << kTestUri;
    }

    InvertedIndex index;
    index.add_book(1, {"car", "boat"});
    MongoIndexWriter writer(kTestUri, kTestDb);
    writer.write(index);

    index.add_book(2, {"car", "dragon"});
    writer.update_terms(index, {"car", "dragon"});

    const auto postings = stage1::mongo_postings_fetcher(kTestUri, kTestDb);
    EXPECT_EQ(postings("car"), (std::vector<int>{1, 2}));  // an existing term, updated
    EXPECT_EQ(postings("dragon"), (std::vector<int>{2}));  // a new term, upserted
    EXPECT_EQ(postings("boat"), (std::vector<int>{1}));    // not given: unchanged
}

TEST(MongoIndexWriter, UpdateTermsWritesEveryTermOfALargeBatch) {
    if (!stage1::mongo_is_reachable(kTestUri)) {
        GTEST_SKIP() << "no MongoDB reachable at " << kTestUri;
    }

    InvertedIndex index;
    MongoIndexWriter writer(kTestUri, kTestDb);
    writer.write(index);  // an empty collection with its unique index
    std::vector<std::string> terms;
    for (int i = 0; i < 10000; ++i) {
        terms.push_back("term" + std::to_string(i));
    }
    index.add_book(7, terms);

    writer.update_terms(index, terms);

    mongocxx::client client{mongocxx::uri{kTestUri}};
    EXPECT_EQ(client[kTestDb]["inverted_index"].count_documents(make_document()), 10000);
    EXPECT_EQ(stage1::mongo_postings_fetcher(kTestUri, kTestDb)("term9999"), (std::vector<int>{7}));
}

TEST(MongoIndexWriter, UpdateTermsWithNoTermsChangesNothing) {
    if (!stage1::mongo_is_reachable(kTestUri)) {
        GTEST_SKIP() << "no MongoDB reachable at " << kTestUri;
    }

    InvertedIndex index;
    index.add_book(1, {"car"});
    MongoIndexWriter writer(kTestUri, kTestDb);
    writer.write(index);

    EXPECT_NO_THROW(writer.update_terms(index, {}));

    EXPECT_EQ(stage1::mongo_postings_fetcher(kTestUri, kTestDb)("car"), (std::vector<int>{1}));
}

TEST(MongoIndexWriter, ClearDropsTheWholeCollection) {
    if (!stage1::mongo_is_reachable(kTestUri)) {
        GTEST_SKIP() << "no MongoDB reachable at " << kTestUri;
    }
    InvertedIndex index;
    index.add_book(1, {"car"});
    MongoIndexWriter writer(kTestUri, kTestDb);
    writer.write(index);

    writer.clear();

    mongocxx::client client{mongocxx::uri{kTestUri}};
    EXPECT_FALSE(client[kTestDb].has_collection("inverted_index"));
}

TEST(MongoPostingsFetcher, ReadsTheGivenDatabase) {
    if (!stage1::mongo_is_reachable(kTestUri)) {
        GTEST_SKIP() << "no MongoDB reachable at " << kTestUri;
    }
    InvertedIndex index;
    index.add_book(3, {"car"});
    index.add_book(1, {"car"});
    MongoIndexWriter(kTestUri, kTestDb).write(index);

    const auto postings = stage1::mongo_postings_fetcher(kTestUri, kTestDb);

    EXPECT_EQ(postings("car"), (std::vector<int>{1, 3}));
    EXPECT_TRUE(postings("dragon").empty());
}

TEST(MongoDiskUsageBytes, PositiveAfterWritingAndZeroForAMissingCollection) {
    if (!stage1::mongo_is_reachable(kTestUri)) {
        GTEST_SKIP() << "no MongoDB reachable at " << kTestUri;
    }
    InvertedIndex index;
    index.add_book(1, {"car", "boat"});
    MongoIndexWriter writer(kTestUri, kTestDb);
    writer.write(index);

    EXPECT_GT(stage1::mongo_disk_usage_bytes(kTestUri, kTestDb), 0);

    writer.clear();
    EXPECT_EQ(stage1::mongo_disk_usage_bytes(kTestUri, kTestDb), 0);
}
