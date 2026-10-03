#pragma once

#include "stage1/crawler/http_client.hpp"

namespace stage1 {

// HttpClient implementation backed by libcurl. This is the only place in the
// project that knows libcurl exists; everything else talks to HttpClient.
class CurlHttpClient : public HttpClient {
public:
    DownloadResult get(const std::string& url) override;
};

}  // namespace stage1
