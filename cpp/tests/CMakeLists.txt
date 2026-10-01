include(GoogleTest)

# A single executable holds all the tests. To add tests: list the .cpp file here.
add_executable(stage1_tests
  smoke_test.cpp
  tokenizer_test.cpp
  stopwords_test.cpp
  book_splitter_test.cpp
  download_result_test.cpp
  gutenberg_client_test.cpp
  metadata_test.cpp
  metadata_store_test.cpp
  book_based_datalake_test.cpp
  range_based_datalake_test.cpp
  time_based_datalake_test.cpp
  inverted_index_test.cpp
  monolithic_index_writer_test.cpp
  hierarchical_index_writer_test.cpp
  mongo_index_writer_test.cpp
  query_engine_test.cpp
  control_log_test.cpp
  book_id_list_test.cpp
  pipeline_test.cpp
  benchmark_test.cpp
  sample_books_test.cpp
  index_build_benchmark_test.cpp
  query_list_test.cpp
  index_query_benchmark_test.cpp
  datalake_write_benchmark_test.cpp
  datalake_lookup_benchmark_test.cpp
  datalake_incremental_benchmark_test.cpp
  datalake_recovery_benchmark_test.cpp
  datalake_storage_benchmark_test.cpp
  metadata_insert_benchmark_test.cpp
  metadata_query_benchmark_test.cpp
  index_update_benchmark_test.cpp
  index_memory_benchmark_test.cpp)
target_link_libraries(stage1_tests PRIVATE stage1_core GTest::gtest_main)
# Lets tests read the shared contract files (stopwords, queries, book ids)
target_compile_definitions(stage1_tests PRIVATE
  STAGE1_SHARED_DIR="${CMAKE_SOURCE_DIR}/../shared")
gtest_discover_tests(stage1_tests)
