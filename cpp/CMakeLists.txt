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
find_package(SQLite3 REQUIRED) # metadata store
find_package(CURL REQUIRED)    # downloads from Project Gutenberg

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
  nlohmann_json::nlohmann_json ${STAGE1_SQLITE_TARGET} CURL::libcurl)
target_compile_options(stage1_deps INTERFACE -Wall -Wextra -Wpedantic)

# ---- Core library: all the project logic; shared by the executable and the tests ----
add_library(stage1_core src/tokenizer.cpp src/stopwords.cpp)
target_link_libraries(stage1_core PUBLIC stage1_deps)

# ---- Executable ----
add_executable(search_engine_stage1 src/main.cpp)
target_link_libraries(search_engine_stage1 PRIVATE stage1_core)

# ---- Tests ----
enable_testing()
add_subdirectory(tests)
