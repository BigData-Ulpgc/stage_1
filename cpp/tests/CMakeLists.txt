include(GoogleTest)

# A single executable holds all the tests. To add tests: list the .cpp file here.
add_executable(stage1_tests
  smoke_test.cpp
  tokenizer_test.cpp
  stopwords_test.cpp
  book_splitter_test.cpp)
target_link_libraries(stage1_tests PRIVATE stage1_core GTest::gtest_main)
# Lets tests read the shared contract files (stopwords, queries, book ids)
target_compile_definitions(stage1_tests PRIVATE
  STAGE1_SHARED_DIR="${CMAKE_SOURCE_DIR}/../shared")
gtest_discover_tests(stage1_tests)
