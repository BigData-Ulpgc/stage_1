// Thin CLI entry point: only parses arguments and dispatches to the real
// commands in cli_commands.cpp. This file has no logic of its own by
// design, the same split the Java module uses between its own thin
// Main.java and SearchEngine.java, which does the equivalent wiring.
#include <cstdlib>
#include <iostream>
#include <string>
#include <string_view>
#include <vector>

#include "stage1/cli_commands.hpp"

namespace {

void print_usage() {
    std::cerr << "usage: search_engine_stage1 [-Dkey=value ...] <command>\n"
                 "commands: pipeline <N> [--offline]\n"
                 "          search <words...>\n"
                 "          status\n"
                 "          config\n"
                 "          benchmark "
                 "<datalake_write|datalake_lookup|datalake_incremental|datalake_recovery|"
                 "datalake_storage|metadata_insert|metadata_query|index_build|index_query|"
                 "index_update|index_memory|index_disk>\n"
                 "keys: datalake.structure=book|range|time  index.structure=monolithic|hierarchical|mongo\n";
}

}  // namespace

int main(int argc, char** argv) {
    try {
        // -Dkey=value arguments come first, like Java's -D options, and win
        // over cpp/config.properties.
        stage1::Properties overrides;
        int first = 1;
        while (first < argc && std::string_view(argv[first]).rfind("-D", 0) == 0) {
            const std::string_view option = std::string_view(argv[first]).substr(2);
            const std::size_t equals = option.find('=');
            if (equals == std::string_view::npos || equals == 0) {
                print_usage();
                return 1;
            }
            overrides[std::string(option.substr(0, equals))] = std::string(option.substr(equals + 1));
            ++first;
        }
        const std::vector<std::string> args(argv + first, argv + argc);

        stage1::AppConfig config;
        try {
            config = stage1::load_cli_config(overrides);
        } catch (const std::invalid_argument& error) {
            std::cerr << "[config] " << error.what() << "\n";
            return 1;
        }

        if ((args.size() == 2 || args.size() == 3) && args[0] == "pipeline") {
            char* end = nullptr;
            const long steps = std::strtol(args[1].c_str(), &end, 10);
            const bool offline = args.size() == 3 && args[2] == "--offline";
            if (end == args[1].c_str() || steps <= 0 || (args.size() == 3 && !offline)) {
                print_usage();
                return 1;
            }
            return stage1::run_pipeline_command(config, static_cast<int>(steps), offline);
        }

        if (args.size() >= 2 && args[0] == "search") {
            // Every word after "search" is part of the query, so both
            // `search whale island` and `search "whale island"` work.
            std::string query = args[1];
            for (std::size_t i = 2; i < args.size(); ++i) {
                query += ' ';
                query += args[i];
            }
            return stage1::run_search_command(config, query);
        }

        if (args.size() == 1 && args[0] == "status") {
            return stage1::run_status_command();
        }

        if (args.size() == 1 && args[0] == "config") {
            return stage1::run_config_command(config);
        }

        if (args.size() == 2 && args[0] == "benchmark") {
            return stage1::run_benchmark_command(args[1]);
        }

        print_usage();
        return 1;
    } catch (const std::exception& error) {
        std::cerr << "[fatal] " << error.what() << "\n";
        return 1;
    }
}
