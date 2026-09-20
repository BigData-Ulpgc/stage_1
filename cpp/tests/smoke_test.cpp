// Checks that the environment is set up: every dependency compiles, links and works.
#include <curl/curl.h>
#include <gtest/gtest.h>
#include <sqlite3.h>

#include <nlohmann/json.hpp>

TEST(Environment, JsonRoundTrip) {
    nlohmann::json index = {{"car", {1, 2, 3}}, {"nice", {1}}};
    auto reparsed = nlohmann::json::parse(index.dump());
    EXPECT_EQ(reparsed["car"].size(), 3u);
}

TEST(Environment, SqliteInMemory) {
    sqlite3* db = nullptr;
    ASSERT_EQ(sqlite3_open(":memory:", &db), SQLITE_OK);
    EXPECT_EQ(sqlite3_exec(db, "CREATE TABLE books(book_id INTEGER PRIMARY KEY);", nullptr, nullptr, nullptr),
              SQLITE_OK);
    sqlite3_close(db);
}

TEST(Environment, CurlInitializes) {
    EXPECT_EQ(curl_global_init(CURL_GLOBAL_DEFAULT), CURLE_OK);
    curl_global_cleanup();
}
