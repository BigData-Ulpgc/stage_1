#include "stage1/config/index_factory.hpp"

#include <nlohmann/json.hpp>

#include <stdexcept>

#include "stage1/datamart/index/hierarchical_index_writer.hpp"
#include "stage1/datamart/index/index_readers.hpp"
#include "stage1/datamart/index/mongo_index_writer.hpp"
#include "stage1/datamart/index/monolithic_index_writer.hpp"
#include "stage1/util/file_io.hpp"

namespace stage1 {

namespace {

[[noreturn]] void unknown_structure(const std::string& structure) {
    throw std::invalid_argument("unknown index structure: " + structure);
}

}  // namespace

std::filesystem::path monolithic_index_path(const std::filesystem::path& data_dir) {
    return data_dir / "datamarts" / "inverted_index.json";
}

std::filesystem::path hierarchical_index_path(const std::filesystem::path& data_dir) {
    return data_dir / "datamarts" / "inverted_index";
}

std::unique_ptr<IndexWriter> create_index_writer(const std::string& structure, const std::filesystem::path& data_dir) {
    if (structure == "monolithic") {
        return std::make_unique<MonolithicIndexWriter>(monolithic_index_path(data_dir));
    }
    if (structure == "hierarchical") {
        return std::make_unique<HierarchicalIndexWriter>(hierarchical_index_path(data_dir));
    }
    if (structure == "mongo") {
        return std::make_unique<MongoIndexWriter>(kMongoUri);  // SPEC section 6's database and collection
    }
    unknown_structure(structure);
}

std::function<std::vector<int>(const std::string&)> open_index(const std::string& structure,
                                                               const std::filesystem::path& data_dir) {
    if (structure == "monolithic") {
        return monolithic_postings_fetcher(monolithic_index_path(data_dir));
    }
    if (structure == "hierarchical") {
        return hierarchical_postings_fetcher(hierarchical_index_path(data_dir));
    }
    if (structure == "mongo") {
        return mongo_postings_fetcher(kMongoUri);
    }
    unknown_structure(structure);
}

bool index_exists(const std::string& structure, const std::filesystem::path& data_dir) {
    if (structure == "monolithic") {
        return std::filesystem::exists(monolithic_index_path(data_dir));
    }
    if (structure == "hierarchical") {
        return std::filesystem::exists(hierarchical_index_path(data_dir));
    }
    if (structure == "mongo") {
        if (!mongo_is_reachable()) {
            throw std::runtime_error(std::string("no MongoDB server reachable at ") + kMongoUri +
                                     " (start it with `docker compose up -d`)");
        }
        return mongo_collection_exists(kMongoUri);
    }
    unknown_structure(structure);
}

std::size_t stored_term_count(const std::string& structure, const std::filesystem::path& data_dir) {
    if (!index_exists(structure, data_dir)) {
        return 0;
    }
    if (structure == "monolithic") {
        return nlohmann::json::parse(read_text_file(monolithic_index_path(data_dir))).size();
    }
    if (structure == "hierarchical") {
        std::size_t files = 0;
        for (const auto& entry : std::filesystem::recursive_directory_iterator(hierarchical_index_path(data_dir))) {
            files += entry.is_regular_file() && entry.path().extension() == ".txt" ? 1 : 0;
        }
        return files;
    }
    return static_cast<std::size_t>(mongo_document_count(kMongoUri));  // "mongo": index_exists checked the rest
}

}  // namespace stage1
