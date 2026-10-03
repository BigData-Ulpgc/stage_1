#include "stage1/crawler/local_file_source.hpp"

#include <fstream>
#include <sstream>
#include <string>

namespace stage1 {

DownloadResult LocalFileSource::fetch(int book_id) {
    const auto path = dir_ / ("pg" + std::to_string(book_id) + ".txt");
    std::ifstream file(path, std::ios::binary);  // binary: keep "\r\n" as is, split_book normalizes it
    if (!file) {
        return DownloadResult::failure("cannot open " + path.string());
    }
    std::ostringstream contents;
    contents << file.rdbuf();
    return DownloadResult::success(contents.str());
}

}  // namespace stage1
