// Thin CLI entry point: only parses arguments and dispatches to the real
// commands in cli_commands.cpp. This file has no logic of its own by
// design, the same split the Java module uses between its own thin
// Main.java and SearchEngine.java, which does the equivalent wiring.
#include <cstdlib>
#include <iostream>
#include <string_view>

#include "stage1/cli_commands.hpp"

namespace {

void print_usage() {
    std::cerr << "usage: search_engine_stage1 pipeline <N>\n"
                 "       search_engine_stage1 benchmark "
                 "<datalake_write|datalake_lookup|datalake_incremental|datalake_recovery|"
                 "datalake_storage|metadata_insert|metadata_query|index_build|index_query|"
                 "index_update|index_memory|index_disk>\n";
}

}  // namespace

int main(int argc, char** argv) {
    try {
        if (argc == 3 && std::string_view(argv[1]) == "pipeline") {
            char* end = nullptr;
            const long steps = std::strtol(argv[2], &end, 10);
            if (end == argv[2] || steps <= 0) {
                print_usage();
                return 1;
            }
            return stage1::run_pipeline_command(static_cast<int>(steps));
        }

        if (argc == 3 && std::string_view(argv[1]) == "benchmark") {
            return stage1::run_benchmark_command(argv[2]);
        }

        print_usage();
        return 1;
    } catch (const std::exception& error) {
        std::cerr << "[fatal] " << error.what() << "\n";
        return 1;
    }
}
