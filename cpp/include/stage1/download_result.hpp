#pragma once

#include <stdexcept>
#include <string>
#include <utility>

namespace stage1 {

// Outcome of an operation that can fail with a human-readable reason, such as
// downloading a book over HTTP. A result is either a success carrying the
// downloaded text, or a failure carrying an error message; never both.
//
// Only one of text()/error() may be called, depending on what ok() reports;
// calling the wrong one is a programming mistake and throws std::logic_error.
class DownloadResult {
public:
    static DownloadResult success(std::string text) { return DownloadResult(true, std::move(text)); }
    static DownloadResult failure(std::string error_message) { return DownloadResult(false, std::move(error_message)); }

    bool ok() const { return ok_; }

    const std::string& text() const {
        if (!ok_) {
            throw std::logic_error("DownloadResult::text() called on a failed result");
        }
        return payload_;
    }

    const std::string& error() const {
        if (ok_) {
            throw std::logic_error("DownloadResult::error() called on a successful result");
        }
        return payload_;
    }

private:
    DownloadResult(bool ok, std::string payload) : ok_(ok), payload_(std::move(payload)) {}

    bool ok_;
    std::string payload_;
};

}  // namespace stage1
