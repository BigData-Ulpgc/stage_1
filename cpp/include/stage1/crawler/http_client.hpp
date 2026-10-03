#pragma once

#include <string>

#include "stage1/crawler/download_result.hpp"

namespace stage1 {

// Abstract contract for "fetch whatever is at this URL over HTTP". Code that
// needs to download something depends on this interface, not on a concrete
// library, so the transport can be swapped (a different HTTP library, or a
// fake client for tests) without touching anyone who only calls get().
class HttpClient {
public:
    virtual ~HttpClient() = default;
    virtual DownloadResult get(const std::string& url) = 0;
};

}  // namespace stage1
