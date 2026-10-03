package es.ulpgc.bigdata.config;

import es.ulpgc.bigdata.datamart.index.HierarchicalFolderIndex;
import es.ulpgc.bigdata.datamart.index.InMemoryInvertedIndex;
import es.ulpgc.bigdata.datamart.index.InvertedIndex;
import es.ulpgc.bigdata.datamart.index.MongoInvertedIndex;
import es.ulpgc.bigdata.datamart.index.MonolithicJsonIndex;

import java.util.List;
import java.util.Objects;

/**
 * Turns a configuration name into an InvertedIndex:
 *
 *   monolithic    -> MonolithicJsonIndex(<data>/datamarts/inverted_index.json)
 *   hierarchical  -> HierarchicalFolderIndex(<data>/datamarts/inverted_index/)
 *   mongo         -> MongoInvertedIndex(mongo.uri, mongo.database, mongo.collection)
 *   memory        -> InMemoryInvertedIndex (not persisted: for testing)
 *
 * Indexer and SearchService receive an InvertedIndex and never know which one it is.
 * The paths are not decided here: they are requested from AppConfig.
 */
public final class InvertedIndexFactory {

    /** Valid names in index.structure. */
    public static final List<String> NAMES = List.of("monolithic", "hierarchical", "mongo", "memory");

    private InvertedIndexFactory() {
    }

    /** The active index (index.structure). */
    public static InvertedIndex create(AppConfig config) {
        return create(config.indexStructure(), config);
    }

    /**
     * Another index with the same configuration (e.g. the benchmark opens all of them).
     * For "mongo" it opens the connection: if Mongo does not answer, it throws an exception within a few seconds.
     *
     * @throws IllegalArgumentException if the name is not a known index
     */
    public static InvertedIndex create(String name, AppConfig config) {
        Objects.requireNonNull(config, "config");
        return switch (Objects.requireNonNull(name, "name")) {
            case "monolithic" -> new MonolithicJsonIndex(config.monolithicIndexFile());
            case "hierarchical" -> new HierarchicalFolderIndex(config.hierarchicalIndexDir());
            case "mongo" -> new MongoInvertedIndex(config.mongoUri(), config.mongoDatabase(), config.mongoCollection());
            case "memory" -> new InMemoryInvertedIndex();
            default -> throw new IllegalArgumentException(
                    "Índice invertido desconocido: \"" + name + "\". Opciones: " + NAMES);
        };
    }
}
