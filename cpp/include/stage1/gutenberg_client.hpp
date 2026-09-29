#pragma once

#include <string>

#include "stage1/book_source.hpp"
#include "stage1/http_client.hpp"

namespace stage1 {

// Builds the Project Gutenberg download URL for a book id (shared/SPEC.md section 2).
// Pure function, no network: kept separate so the URL format is testable on its own.
std::string book_download_url(int book_id);

// BookSource backed by Project Gutenberg. Does not own the HttpClient: the
// caller chooses which transport to use (the real CurlHttpClient, or a fake
// for tests) and must keep it alive for as long as this object is used.
class GutenbergSource : public BookSource {
public:
    explicit GutenbergSource(HttpClient& client) : client_(client) {}

    DownloadResult fetch(int book_id) override;

private:
    HttpClient& client_;
};

}  // namespace stage1
