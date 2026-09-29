#include "stage1/control_log.hpp"

#include <charconv>
#include <fstream>
#include <stdexcept>

#include "stage1/text_utils.hpp"

namespace stage1 {

ControlLog::ControlLog(std::filesystem::path path) : path_(std::move(path)) {
    std::filesystem::create_directories(path_.parent_path());

    std::ifstream file(path_);
    if (!file) {
        return;  // no log file yet: nothing has been recorded so far
    }

    std::string line;
    while (std::getline(file, line)) {
        const std::string_view trimmed = trim(line);
        if (trimmed.empty()) {
            continue;
        }
        int book_id = 0;
        const auto result = std::from_chars(trimmed.data(), trimmed.data() + trimmed.size(), book_id);
        if (result.ec == std::errc{}) {
            recorded_.insert(book_id);
        }
    }
}

bool ControlLog::contains(int book_id) const { return recorded_.count(book_id) > 0; }

void ControlLog::mark(int book_id) {
    if (recorded_.count(book_id) > 0) {
        return;  // already recorded: appending again would only duplicate the line
    }

    std::ofstream file(path_, std::ios::app);
    if (!file) {
        throw std::runtime_error("cannot open control log for appending: " + path_.string());
    }
    file << book_id << '\n';
    if (!file) {
        throw std::runtime_error("failed appending to control log: " + path_.string());
    }

    recorded_.insert(book_id);
}

ControlDecision next_control_action(const std::vector<int>& candidate_ids, const ControlLog& downloaded,
                                     const ControlLog& indexed) {
    for (int book_id : candidate_ids) {
        if (downloaded.contains(book_id) && !indexed.contains(book_id)) {
            return ControlDecision{ControlAction::IndexBook, book_id};
        }
    }
    for (int book_id : candidate_ids) {
        if (!downloaded.contains(book_id)) {
            return ControlDecision{ControlAction::DownloadBook, book_id};
        }
    }
    return ControlDecision{ControlAction::Nothing, 0};
}

}  // namespace stage1
