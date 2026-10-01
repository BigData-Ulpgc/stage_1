#include "stage1/hierarchical_index_writer.hpp"

#include <cctype>
#include <sstream>

#include "stage1/file_io.hpp"

namespace stage1 {

std::string hierarchical_folder_name(const std::string& term) {
    // unsigned char before toupper: char can be negative on this platform (a
    // non-ASCII byte), and toupper's behavior is undefined for such values.
    // Not reachable in practice for a real term (tokenize only ever produces
    // [a-z0-9]), but it costs nothing to make the function itself safe.
    const unsigned char first = static_cast<unsigned char>(term.front());
    return std::string(1, static_cast<char>(std::toupper(first)));
}

namespace {

// "one book id per line" (SPEC section 6), e.g. postings {1, 3} -> "1\n3\n".
std::string postings_file_content(const std::vector<int>& postings) {
    std::ostringstream out;
    for (int book_id : postings) {
        out << book_id << '\n';
    }
    return out.str();
}

}  // namespace

void HierarchicalIndexWriter::write(const InvertedIndex& index) {
    for (const auto& entry : index.entries()) {
        const std::filesystem::path term_path = root_ / hierarchical_folder_name(entry.term) / (entry.term + ".txt");
        write_text_file(term_path, postings_file_content(entry.postings));
    }
}

void HierarchicalIndexWriter::update_terms(const InvertedIndex& index, const std::vector<std::string>& changed_terms) {
    // Exactly what SPEC section 6 calls this layout's own advantage ("very
    // fine-grained updates: only the file of the affected term is
    // modified"): touch only the files for `changed_terms`, read `index`
    // fresh for each one's current postings, and leave every other term's
    // file untouched on disk.
    for (const auto& term : changed_terms) {
        const std::filesystem::path term_path = root_ / hierarchical_folder_name(term) / (term + ".txt");
        write_text_file(term_path, postings_file_content(index.postings(term)));
    }
}

}  // namespace stage1
