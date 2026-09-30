package es.ulpgc.bigdata.config;

import es.ulpgc.bigdata.datamart.index.HierarchicalFolderIndex;
import es.ulpgc.bigdata.datamart.index.InMemoryInvertedIndex;
import es.ulpgc.bigdata.datamart.index.InvertedIndex;
import es.ulpgc.bigdata.datamart.index.MongoInvertedIndex;
import es.ulpgc.bigdata.datamart.index.MonolithicJsonIndex;

import java.util.List;
import java.util.Objects;

/**
 * Traduce un nombre de configuración a un InvertedIndex:
 *
 *   monolithic    -> MonolithicJsonIndex(<data>/datamarts/inverted_index.json)
 *   hierarchical  -> HierarchicalFolderIndex(<data>/datamarts/inverted_index/)
 *   mongo         -> MongoInvertedIndex(mongo.uri, mongo.database, mongo.collection)
 *   memory        -> InMemoryInvertedIndex (no persiste: para pruebas)
 *
 * Indexer y SearchService reciben un InvertedIndex y nunca saben cuál es.
 * Las rutas no se deciden aquí: se piden a AppConfig.
 */
public final class InvertedIndexFactory {

    /** Nombres válidos en index.structure. */
    public static final List<String> NAMES = List.of("monolithic", "hierarchical", "mongo", "memory");

    private InvertedIndexFactory() {
    }

    /** El índice activo (index.structure). */
    public static InvertedIndex create(AppConfig config) {
        return create(config.indexStructure(), config);
    }

    /**
     * Otro índice con la misma configuración (p. ej. el benchmark los abre todos).
     * Para "mongo" abre la conexión: si Mongo no responde, lanza excepción en unos segundos.
     *
     * @throws IllegalArgumentException si el nombre no es un índice conocido
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
