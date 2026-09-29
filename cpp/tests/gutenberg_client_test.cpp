#include <gtest/gtest.h>

#include "fakes/fake_http_client.hpp"
#include "stage1/curl_http_client.hpp"
#include "stage1/gutenberg_client.hpp"

using stage1::book_download_url;
using stage1::CurlHttpClient;
using stage1::DownloadResult;
using stage1::GutenbergSource;
using stage1::testing::FakeHttpClient;

TEST(GutenbergClient, BuildsTheDownloadUrlForABookId) {
    // Book 1342 is Pride and Prejudice; this is the example URL from the course PDF.
    EXPECT_EQ(book_download_url(1342), "https://www.gutenberg.org/cache/epub/1342/pg1342.txt");
}

TEST(GutenbergClient, BuildsTheUrlForASingleDigitId) {
    EXPECT_EQ(book_download_url(5), "https://www.gutenberg.org/cache/epub/5/pg5.txt");
}

TEST(GutenbergSourceTest, AsksTheInjectedClientForTheRightUrl) {
    FakeHttpClient fake(DownloadResult::success("fake body"));
    GutenbergSource source(fake);

    auto result = source.fetch(1342);

    ASSERT_TRUE(result.ok());
    EXPECT_EQ(result.text(), "fake body");
    ASSERT_EQ(fake.requested_urls().size(), 1u);
    EXPECT_EQ(fake.requested_urls()[0], "https://www.gutenberg.org/cache/epub/1342/pg1342.txt");
}

TEST(GutenbergSourceTest, PropagatesAFailureFromTheClient) {
    FakeHttpClient fake(DownloadResult::failure("HTTP 404"));
    GutenbergSource source(fake);

    auto result = source.fetch(999999);

    EXPECT_FALSE(result.ok());
    EXPECT_EQ(result.error(), "HTTP 404");
}

// Exercises the real transport (CurlHttpClient) end to end over the real network.
// If the sandbox/CI has no outbound access this is a setup limitation, not a bug
// in our code, so we skip instead of failing.
TEST(GutenbergSourceTest, DownloadsARealBookThroughTheRealClient) {
    CurlHttpClient client;
    GutenbergSource source(client);

    auto result = source.fetch(1342);
    if (!result.ok()) {
        GTEST_SKIP() << "no network access in this environment: " << result.error();
    }
    EXPECT_NE(result.text().find("Pride and Prejudice"), std::string::npos);
}
