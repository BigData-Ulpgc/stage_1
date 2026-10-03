include(GoogleTest)

# A single executable holds all the tests. To add tests: list the .cpp file here.
add_executable(stage1_tests
  smoke_test.cpp
  datamart/index/tokenizer_test.cpp
  datamart/index/stopwords_test.cpp
  crawler/book_splitter_test.cpp
  crawler/download_result_test.cpp
  crawler/gutenberg_client_test.cpp
  crawler/local_file_source_test.cpp
  datamart/metadata/metadata_test.cpp
  datamart/metadata/metadata_store_test.cpp
  datalake/book_based_datalake_test.cpp
  datalake/range_based_datalake_test.cpp
  datalake/time_based_datalake_test.cpp
  datamart/index/inverted_index_test.cpp
  datamart/index/monolithic_index_writer_test.cpp
  datamart/index/hierarchical_index_writer_test.cpp
  datamart/index/mongo_index_writer_test.cpp
  query/query_engine_test.cpp
  datamart/index/index_readers_test.cpp
  control/control_log_test.cpp
  control/book_id_list_test.cpp
  control/pipeline_test.cpp
  benchmark/benchmark_test.cpp
  benchmark/simulated_clock_test.cpp
  benchmark/datalake_benchmark_support_test.cpp
  benchmark/metadata_benchmark_support_test.cpp
  benchmark/index_benchmark_support_test.cpp
  benchmark/java_random_test.cpp
  benchmark/sample_books_test.cpp
  benchmark/index_build_benchmark_test.cpp
  benchmark/query_list_test.cpp
  benchmark/index_query_benchmark_test.cpp
  benchmark/datalake_write_benchmark_test.cpp
  benchmark/datalake_lookup_benchmark_test.cpp
  benchmark/datalake_incremental_benchmark_test.cpp
  benchmark/datalake_recovery_benchmark_test.cpp
  benchmark/datalake_storage_benchmark_test.cpp
  benchmark/metadata_insert_benchmark_test.cpp
  benchmark/metadata_query_benchmark_test.cpp
  benchmark/index_update_benchmark_test.cpp
  benchmark/index_memory_benchmark_test.cpp
  benchmark/index_disk_benchmark_test.cpp)
target_link_libraries(stage1_tests PRIVATE stage1_core GTest::gtest_main)
# Lets tests in any subfolder (crawler/, datalake/, ...) include the shared
# helpers as "fakes/..." and "support/...", not only those sitting in tests/.
target_include_directories(stage1_tests PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
# Lets tests read the shared contract files (stopwords, queries, book ids)
target_compile_definitions(stage1_tests PRIVATE
  STAGE1_SHARED_DIR="${CMAKE_SOURCE_DIR}/../shared")
gtest_discover_tests(stage1_tests)
