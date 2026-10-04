#include <gtest/gtest.h>

#include <stdexcept>

#include "stage1/config/app_config.hpp"
#include "stage1/util/file_io.hpp"
#include "support/temp_dir.hpp"

using stage1::AppConfig;
using stage1::load_config;
using stage1::parse_properties;
using stage1::Properties;
using stage1::testing::TempDir;

TEST(ParseProperties, ReadsKeyValueLinesAndSkipsCommentsAndBlankLines) {
    const auto properties = parse_properties(
        "# a comment\n"
        "! another comment\n"
        "\n"
        "  datalake.structure =  book  \n"
        "index.structure=hierarchical\n");

    EXPECT_EQ(properties, (Properties{{"datalake.structure", "book"}, {"index.structure", "hierarchical"}}));
}

TEST(ParseProperties, RejectsALineWithoutEquals) {
    EXPECT_THROW(parse_properties("datalake.structure book\n"), std::invalid_argument);
}

TEST(LoadConfig, UsesJavasDefaultsWhenThereIsNoFile) {
    TempDir root("stage1_app_config_test_defaults");

    const AppConfig config = load_config(root.path() / "missing.properties", {});

    EXPECT_EQ(config.datalake_structure, "time");
    EXPECT_EQ(config.index_structure, "monolithic");
}

TEST(LoadConfig, TheFileOverridesTheDefaultsAndDashDOverridesTheFile) {
    TempDir root("stage1_app_config_test_overrides");
    const auto file = root.path() / "config.properties";
    stage1::write_text_file(file, "datalake.structure = book\nindex.structure = hierarchical\n");

    const AppConfig from_file = load_config(file, {});
    const AppConfig overridden = load_config(file, {{"index.structure", "mongo"}});

    EXPECT_EQ(from_file.datalake_structure, "book");
    EXPECT_EQ(from_file.index_structure, "hierarchical");
    EXPECT_EQ(overridden.datalake_structure, "book");
    EXPECT_EQ(overridden.index_structure, "mongo");
}

TEST(LoadConfig, RejectsAnUnknownKeyAndAStructureThatDoesNotExist) {
    TempDir root("stage1_app_config_test_errors");
    const auto missing = root.path() / "missing.properties";

    EXPECT_THROW(load_config(missing, {{"index.strucutre", "mongo"}}), std::invalid_argument);  // typo in the key
    EXPECT_THROW(load_config(missing, {{"datalake.structure", "hash"}}), std::invalid_argument);
    EXPECT_THROW(load_config(missing, {{"index.structure", "memory"}}), std::invalid_argument);
}

TEST(Describe, ShowsBothKeysWithTheirValues) {
    AppConfig config;
    config.datalake_structure = "range";

    EXPECT_EQ(stage1::describe(config), "datalake.structure = range\nindex.structure = monolithic\n");
}
