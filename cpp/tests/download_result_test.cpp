#include <gtest/gtest.h>

#include "stage1/download_result.hpp"

using stage1::DownloadResult;

TEST(DownloadResult, SuccessCarriesTheText) {
    auto result = DownloadResult::success("Hello world");
    EXPECT_TRUE(result.ok());
    EXPECT_EQ(result.text(), "Hello world");
}

TEST(DownloadResult, FailureCarriesTheErrorMessage) {
    auto result = DownloadResult::failure("HTTP 404");
    EXPECT_FALSE(result.ok());
    EXPECT_EQ(result.error(), "HTTP 404");
}

TEST(DownloadResult, ReadingTextOnAFailureThrows) {
    auto result = DownloadResult::failure("HTTP 404");
    EXPECT_THROW(result.text(), std::logic_error);
}

TEST(DownloadResult, ReadingErrorOnASuccessThrows) {
    auto result = DownloadResult::success("Hello world");
    EXPECT_THROW(result.error(), std::logic_error);
}
