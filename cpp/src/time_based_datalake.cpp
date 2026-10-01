#include "stage1/time_based_datalake.hpp"

#include <cstdio>
#include <ctime>

#include "stage1/file_io.hpp"

namespace stage1 {

std::string time_folder_name(std::chrono::system_clock::time_point time) {
    const std::time_t seconds = std::chrono::system_clock::to_time_t(time);

    // localtime_r/localtime_s are the thread-safe variants of std::localtime,
    // which writes into a shared static buffer and is unsafe to call from more
    // than one thread at a time. This pipeline is single-threaded today, but
    // getting it right now costs nothing and avoids a trap for a future stage.
    std::tm local_time{};
#if defined(_WIN32)
    localtime_s(&local_time, &seconds);
#else
    localtime_r(&seconds, &local_time);
#endif

    char buffer[16];  // "YYYYMMDD/HH" is 11 characters + '\0'; 16 leaves margin
    std::snprintf(buffer, sizeof(buffer), "%04d%02d%02d/%02d", local_time.tm_year + 1900, local_time.tm_mon + 1,
                  local_time.tm_mday, local_time.tm_hour);
    return std::string(buffer);
}

BookLocation TimeBasedDatalake::write(int book_id, const std::string& header, const std::string& body) {
    const std::filesystem::path time_dir = root_ / time_folder_name(clock_.now());
    const std::string id = std::to_string(book_id);
    const std::filesystem::path body_path = time_dir / (id + ".body.txt");
    const std::filesystem::path header_path = time_dir / (id + ".header.txt");

    write_text_file(body_path, body);
    write_text_file(header_path, header);

    const BookLocation location{body_path.string(), header_path.string()};
    written_[book_id] = location;  // remember it: this is the only way locate() can ever find it again
    return location;
}

std::optional<BookLocation> TimeBasedDatalake::locate(int book_id) const {
    const auto it = written_.find(book_id);
    if (it == written_.end()) {
        return std::nullopt;
    }
    return it->second;
}

}  // namespace stage1
