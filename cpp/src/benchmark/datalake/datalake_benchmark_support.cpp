#include "stage1/benchmark/datalake/datalake_benchmark_support.hpp"

#include <algorithm>
#include <stdexcept>

#include "stage1/benchmark/datalake/simulated_clock.hpp"

namespace stage1 {

std::unique_ptr<Datalake> fresh_datalake(const std::string& structure, const std::filesystem::path& dir) {
    // Checked before anything is deleted: an unknown name must not cost a folder.
    if (std::find(kDatalakeStructures.begin(), kDatalakeStructures.end(), structure) == kDatalakeStructures.end()) {
        throw std::invalid_argument("unknown datalake structure: " + structure);
    }
    std::filesystem::remove_all(dir);
    return create_datalake(structure, dir, std::make_unique<SimulatedClock>(SimulatedClock::ten_books_per_hour()));
}

}  // namespace stage1
