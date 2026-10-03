#pragma once

#include <string>
#include <utility>
#include <vector>

#include "stage1/crawler/http_client.hpp"

namespace stage1::testing {

// Test double for HttpClient: instead of making a real HTTP request, it
// returns a fixed, caller-chosen DownloadResult and records every URL it was
// asked to fetch, so a test can check what the code under test requested.
class FakeHttpClient : public HttpClient {
public:
    explicit FakeHttpClient(DownloadResult canned_result) : canned_result_(std::move(canned_result)) {}

    DownloadResult get(const std::string& url) override {
        requested_urls_.push_back(url);
        return canned_result_;
    }

    const std::vector<std::string>& requested_urls() const { return requested_urls_; }

private:
    DownloadResult canned_result_;
    std::vector<std::string> requested_urls_;
};

}  // namespace stage1::testing
