cmake_minimum_required(VERSION 3.20)
project(search_engine_stage1 VERSION 1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON) # generates compile_commands.json for the IDE

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release) # benchmarks must be measured with optimizations on
endif()

include(FetchContent)
set(FETCHCONTENT_QUIET OFF)

# ---- Dependencies downloaded automatically (nothing to install by hand) ----
FetchContent_Declare(nlohmann_json
  URL https://github.com/nlohmann/json/releases/download/v3.12.0/json.tar.xz
  DOWNLOAD_EXTRACT_TIMESTAMP TRUE)

FetchContent_Declare(googletest
  URL https://github.com/google/googletest/archive/refs/tags/v1.17.0.tar.gz
  DOWNLOAD_EXTRACT_TIMESTAMP TRUE)

set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(nlohmann_json googletest)

# ---- System dependencies ----
find_package(SQLite3 REQUIRED)     # metadata store
find_package(CURL REQUIRED)        # downloads from Project Gutenberg
find_package(mongocxx REQUIRED)    # "mongo" inverted-index structure (SPEC section 6)
find_package(bsoncxx REQUIRED)     # BSON documents used by mongocxx

# Recent CMake (seen with 4.4.3) deprecates SQLite::SQLite3 for SQLite3::SQLite3; support both
if(TARGET SQLite3::SQLite3)
  set(STAGE1_SQLITE_TARGET SQLite3::SQLite3)
else()
  set(STAGE1_SQLITE_TARGET SQLite::SQLite3)
endif()

# Umbrella target: everything the project code needs to compile and link
add_library(stage1_deps INTERFACE)
target_include_directories(stage1_deps INTERFACE ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(stage1_deps INTERFACE
  nlohmann_json::nlohmann_json ${STAGE1_SQLITE_TARGET} CURL::libcurl
  mongo::mongocxx_shared mongo::bsoncxx_shared)
target_compile_options(stage1_deps INTERFACE -Wall -Wextra -Wpedantic)

# ---- Core library: all the project logic; shared by the executable and the tests ----
add_library(stage1_core
  src/datamart/index/tokenizer.cpp
  src/datamart/index/stopwords.cpp
  src/crawler/book_splitter.cpp
  src/crawler/curl_http_client.cpp
  src/crawler/gutenberg_client.cpp
  src/crawler/local_file_source.cpp
  src/datamart/metadata/metadata.cpp
  src/datamart/metadata/metadata_store.cpp
  src/util/file_io.cpp
  src/datalake/book_based_datalake.cpp
  src/datalake/range_based_datalake.cpp
  src/datalake/time_based_datalake.cpp
  src/datamart/index/inverted_index.cpp
  src/datamart/index/monolithic_index_writer.cpp
  src/datamart/index/hierarchical_index_writer.cpp
  src/datamart/index/mongo_index_writer.cpp
  src/query/query_engine.cpp
  src/datamart/index/index_readers.cpp
  src/control/control_log.cpp
  src/control/book_id_list.cpp
  src/control/pipeline.cpp
  src/benchmark/benchmark.cpp
  src/benchmark/java_random.cpp
  src/benchmark/sample_books.cpp
  src/benchmark/datalake/datalake_benchmark_support.cpp
  src/benchmark/datalake/datalake_incremental_benchmark.cpp
  src/benchmark/datalake/datalake_lookup_benchmark.cpp
  src/benchmark/datalake/datalake_recovery_benchmark.cpp
  src/benchmark/datalake/datalake_storage_benchmark.cpp
  src/benchmark/datalake/datalake_write_benchmark.cpp
  src/benchmark/datalake/simulated_clock.cpp
  src/benchmark/index/index_benchmark_support.cpp
  src/benchmark/index/index_build_benchmark.cpp
  src/benchmark/index/index_disk_benchmark.cpp
  src/benchmark/index/index_memory_benchmark.cpp
  src/benchmark/index/index_query_benchmark.cpp
  src/benchmark/index/index_update_benchmark.cpp
  src/benchmark/index/query_list.cpp
  src/benchmark/metadata/metadata_benchmark_support.cpp
  src/benchmark/metadata/metadata_insert_benchmark.cpp
  src/benchmark/metadata/metadata_query_benchmark.cpp)
target_link_libraries(stage1_core PUBLIC stage1_deps)

# ---- Executable ----
# cli_commands.cpp belongs here, not in stage1_core: it is the only code that
# reads the directory macros below, which only this target defines.
add_executable(search_engine_stage1
  src/main.cpp
  src/cli_commands.cpp)
target_link_libraries(search_engine_stage1 PRIVATE stage1_core)
# Lets cli_commands.cpp find shared/ and its own data/ directory regardless of
# the working directory it is launched from (same pattern as the tests' own
# STAGE1_SHARED_DIR, see tests/CMakeLists.txt).
target_compile_definitions(search_engine_stage1 PRIVATE
  STAGE1_SHARED_DIR="${CMAKE_SOURCE_DIR}/../shared"
  STAGE1_SAMPLE_DIR="${CMAKE_SOURCE_DIR}/../sample_dataset"
  STAGE1_DATA_DIR="${CMAKE_SOURCE_DIR}/data"
  STAGE1_BENCHMARKS_DIR="${CMAKE_SOURCE_DIR}/benchmarks")

# ---- Tests ----
enable_testing()
add_subdirectory(tests)
