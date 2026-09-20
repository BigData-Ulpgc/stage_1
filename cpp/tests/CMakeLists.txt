include(GoogleTest)

# A single executable holds all the tests. To add tests: list the .cpp file here.
add_executable(stage1_tests
  smoke_test.cpp
  tokenizer_test.cpp)
target_link_libraries(stage1_tests PRIVATE stage1_core GTest::gtest_main)
gtest_discover_tests(stage1_tests)
