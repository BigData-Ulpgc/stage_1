#include "stage1/curl_http_client.hpp"

#include <curl/curl.h>

#include <stdexcept>
#include <string>

namespace stage1 {

namespace {

// Owns a CURL* handle: creates it in the constructor, always destroys it in the
// destructor. Copying is disabled because two handles must never share one CURL*.
class CurlHandle {
public:
    CurlHandle() : handle_(curl_easy_init()) {
        if (!handle_) {
            throw std::runtime_error("curl_easy_init failed");
        }
    }
    ~CurlHandle() { curl_easy_cleanup(handle_); }

    CurlHandle(const CurlHandle&) = delete;
    CurlHandle& operator=(const CurlHandle&) = delete;

    CURL* get() const { return handle_; }

private:
    CURL* handle_;
};

// libcurl calls this every time it receives a chunk of the response body.
// `user_data` is whatever pointer was registered with CURLOPT_WRITEDATA; here it
// is always a std::string* that we grow with the new bytes.
std::size_t write_callback(char* data, std::size_t item_size, std::size_t item_count, void* user_data) {
    const std::size_t byte_count = item_size * item_count;
    auto* buffer = static_cast<std::string*>(user_data);
    buffer->append(data, byte_count);
    return byte_count;
}

}  // namespace

DownloadResult CurlHttpClient::get(const std::string& url) {
    CurlHandle curl;
    std::string body;

    curl_easy_setopt(curl.get(), CURLOPT_URL, url.c_str());
    curl_easy_setopt(curl.get(), CURLOPT_WRITEFUNCTION, write_callback);
    curl_easy_setopt(curl.get(), CURLOPT_WRITEDATA, &body);
    curl_easy_setopt(curl.get(), CURLOPT_FOLLOWLOCATION, 1L);
    curl_easy_setopt(curl.get(), CURLOPT_USERAGENT, "stage1-search-engine/1.0");

    const CURLcode outcome = curl_easy_perform(curl.get());
    if (outcome != CURLE_OK) {
        return DownloadResult::failure(curl_easy_strerror(outcome));
    }

    long status = 0;
    curl_easy_getinfo(curl.get(), CURLINFO_RESPONSE_CODE, &status);
    if (status < 200 || status >= 300) {
        return DownloadResult::failure("HTTP " + std::to_string(status));
    }

    return DownloadResult::success(std::move(body));
}

}  // namespace stage1
