#include "stage1/crawler/gutenberg_client.hpp"

#include <string>

namespace stage1 {

std::string book_download_url(int book_id) {
    const std::string id = std::to_string(book_id);
    return "https://www.gutenberg.org/cache/epub/" + id + "/pg" + id + ".txt";
}

DownloadResult GutenbergSource::fetch(int book_id) { return client_.get(book_download_url(book_id)); }

}  // namespace stage1
