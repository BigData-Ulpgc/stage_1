package es.ulpgc.bigdata.config;

import es.ulpgc.bigdata.datamart.index.MongoInvertedIndex;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * TODA la configuración del programa, y el ÚNICO sitio que sabe dónde vive cada cosa:
 *
 *   <data>/datalake/<estructura>/            DatalakeFactory
 *   <data>/datamarts/metadata.db             SQLite
 *   <data>/datamarts/inverted_index.json     índice monolithic
 *   <data>/datamarts/inverted_index/         índice hierarchical
 *   <data>/control/                          ControlFiles
 *   <shared>/book_ids.txt, stopwords.txt, queries.txt
 *   <benchmarks>/results/, <benchmarks>/work/
 *
 * Ninguna otra clase escribe "data/..." ni "inverted_index.json": piden la ruta aquí.
 *
 * De dónde sale cada valor (lo de abajo gana a lo de arriba):
 *   1. los valores por defecto de esta clase
 *   2. el fichero .properties (config.properties si existe, o el de --config)
 *   3. la variable de entorno MONGO_URI (la que usan docker y los tests)
 *   4. las propiedades del sistema: java -Dindex.structure=hierarchical ...
 */
public record AppConfig(Path dataDir, Path sharedDir, Path benchmarksDir,
                        String datalakeStructure, String indexStructure,
                        String mongoUri, String mongoDatabase, String mongoCollection,
                        Duration connectTimeout, Duration requestTimeout) {

    // --- Claves del fichero .properties ---
    public static final String DATA_DIR = "data.dir";
    public static final String SHARED_DIR = "shared.dir";
    public static final String BENCHMARKS_DIR = "benchmarks.dir";
    public static final String DATALAKE_STRUCTURE = "datalake.structure";
    public static final String INDEX_STRUCTURE = "index.structure";
    public static final String MONGO_URI = "mongo.uri";
    public static final String MONGO_DATABASE = "mongo.database";
    public static final String MONGO_COLLECTION = "mongo.collection";
    public static final String CONNECT_TIMEOUT = "http.connect.timeout.seconds";
    public static final String REQUEST_TIMEOUT = "http.request.timeout.seconds";

    /** Todas las claves, en el orden en que se muestran. */
    public static final List<String> KEYS = List.of(DATA_DIR, SHARED_DIR, BENCHMARKS_DIR,
            DATALAKE_STRUCTURE, INDEX_STRUCTURE, MONGO_URI, MONGO_DATABASE, MONGO_COLLECTION,
            CONNECT_TIMEOUT, REQUEST_TIMEOUT);

    /** Fichero que se lee si no se indica otro y existe en la carpeta actual. */
    public static final Path DEFAULT_FILE = Path.of("config.properties");

    /** Valores por defecto: pensados para ejecutar desde java/stage1. */
    private static final Map<String, String> DEFAULTS = Map.of(
            DATA_DIR, "data",
            SHARED_DIR, "../../shared",
            BENCHMARKS_DIR, "benchmarks",
            DATALAKE_STRUCTURE, "time",
            INDEX_STRUCTURE, "monolithic",
            MONGO_URI, "mongodb://localhost:27017",
            MONGO_DATABASE, MongoInvertedIndex.DEFAULT_DATABASE,
            MONGO_COLLECTION, MongoInvertedIndex.DEFAULT_COLLECTION,
            CONNECT_TIMEOUT, "10",
            REQUEST_TIMEOUT, "15");

    /** Comprueba los valores al crearse: un error de configuración sale al arrancar, no a mitad. */
    public AppConfig {
        Objects.requireNonNull(dataDir, DATA_DIR);
        Objects.requireNonNull(sharedDir, SHARED_DIR);
        Objects.requireNonNull(benchmarksDir, BENCHMARKS_DIR);
        requireOneOf(DATALAKE_STRUCTURE, datalakeStructure, DatalakeFactory.NAMES);
        requireOneOf(INDEX_STRUCTURE, indexStructure, InvertedIndexFactory.NAMES);
        Objects.requireNonNull(mongoUri, MONGO_URI);
        Objects.requireNonNull(mongoDatabase, MONGO_DATABASE);
        Objects.requireNonNull(mongoCollection, MONGO_COLLECTION);
        requirePositive(CONNECT_TIMEOUT, connectTimeout);
        requirePositive(REQUEST_TIMEOUT, requestTimeout);
    }

    // ------------------------------------------------------------------
    // Cómo se crea
    // ------------------------------------------------------------------

    /** Sólo los valores por defecto (sin fichero, entorno ni -D): útil en tests. */
    public static AppConfig defaults() {
        return fromProperties(new Properties());
    }

    /** config.properties si existe + MONGO_URI + -D. Lo que usan los main. */
    public static AppConfig load() {
        return load(Files.exists(DEFAULT_FILE) ? DEFAULT_FILE : null);
    }

    /** Este fichero (null = ninguno) + MONGO_URI + -D. */
    public static AppConfig load(Path file) {
        Properties merged = new Properties();
        if (file != null) {
            merged.putAll(readFile(file));
        }
        String envMongo = System.getenv("MONGO_URI");
        if (envMongo != null && !envMongo.isBlank()) {
            merged.setProperty(MONGO_URI, envMongo);
        }
        for (String key : KEYS) {
            String value = System.getProperty(key);
            if (value != null) {
                merged.setProperty(key, value);
            }
        }
        return fromProperties(merged);
    }

    /** Las claves que falten toman el valor por defecto; las desconocidas son un error (erratas). */
    public static AppConfig fromProperties(Properties p) {
        for (String key : p.stringPropertyNames()) {
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("Clave de configuración desconocida: " + key + ". Válidas: " + KEYS);
            }
        }
        return new AppConfig(
                Path.of(get(p, DATA_DIR)),
                Path.of(get(p, SHARED_DIR)),
                Path.of(get(p, BENCHMARKS_DIR)),
                get(p, DATALAKE_STRUCTURE),
                get(p, INDEX_STRUCTURE),
                get(p, MONGO_URI),
                get(p, MONGO_DATABASE),
                get(p, MONGO_COLLECTION),
                seconds(p, CONNECT_TIMEOUT),
                seconds(p, REQUEST_TIMEOUT));
    }

    /** La misma configuración con otra carpeta de datos (los benchmarks trabajan en su propia carpeta). */
    public AppConfig withDataDir(Path newDataDir) {
        return new AppConfig(newDataDir, sharedDir, benchmarksDir, datalakeStructure, indexStructure,
                mongoUri, mongoDatabase, mongoCollection, connectTimeout, requestTimeout);
    }

    // ------------------------------------------------------------------
    // Rutas: el único sitio donde se construyen
    // ------------------------------------------------------------------

    /** <data>/datalake/<estructura>: cada estructura en su carpeta, así pueden convivir. */
    public Path datalakeDir(String structure) {
        return dataDir.resolve("datalake").resolve(structure);
    }

    /** La carpeta de la estructura activa. */
    public Path datalakeDir() {
        return datalakeDir(datalakeStructure);
    }

    public Path datamartsDir() {
        return dataDir.resolve("datamarts");
    }

    public Path metadataDb() {
        return datamartsDir().resolve("metadata.db");
    }

    public Path monolithicIndexFile() {
        return datamartsDir().resolve("inverted_index.json");
    }

    public Path hierarchicalIndexDir() {
        return datamartsDir().resolve("inverted_index");
    }

    public Path controlDir() {
        return dataDir.resolve("control");
    }

    public Path bookIdsFile() {
        return sharedDir.resolve("book_ids.txt");
    }

    public Path stopwordsFile() {
        return sharedDir.resolve("stopwords.txt");
    }

    public Path queriesFile() {
        return sharedDir.resolve("queries.txt");
    }

    public Path benchmarkResultsDir() {
        return benchmarksDir.resolve("results");
    }

    /** Carpeta de trabajo de un benchmark: <benchmarks>/work/<nombre>. */
    public Path benchmarkWorkDir(String benchmark) {
        return benchmarksDir.resolve("work").resolve(benchmark);
    }

    /** Una línea por clave, con el valor efectivo (comando "config" de Main). */
    public String describe() {
        return String.join("\n",
                DATA_DIR + " = " + dataDir,
                SHARED_DIR + " = " + sharedDir,
                BENCHMARKS_DIR + " = " + benchmarksDir,
                DATALAKE_STRUCTURE + " = " + datalakeStructure + "   -> " + datalakeDir(),
                INDEX_STRUCTURE + " = " + indexStructure,
                MONGO_URI + " = " + mongoUri,
                MONGO_DATABASE + " = " + mongoDatabase,
                MONGO_COLLECTION + " = " + mongoCollection,
                CONNECT_TIMEOUT + " = " + connectTimeout.toSeconds(),
                REQUEST_TIMEOUT + " = " + requestTimeout.toSeconds());
    }

    // ------------------------------------------------------------------
    // Auxiliares
    // ------------------------------------------------------------------

    private static Properties readFile(Path file) {
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(reader);
            return p;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer la configuración " + file, e);
        }
    }

    private static String get(Properties p, String key) {
        return p.getProperty(key, DEFAULTS.get(key)).strip();
    }

    private static Duration seconds(Properties p, String key) {
        String value = get(p, key);
        try {
            return Duration.ofSeconds(Long.parseLong(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " debe ser un número de segundos: \"" + value + "\"");
        }
    }

    private static void requireOneOf(String key, String value, List<String> valid) {
        if (!valid.contains(value)) {
            throw new IllegalArgumentException(key + " = \"" + value + "\" no existe. Opciones: " + valid);
        }
    }

    private static void requirePositive(String key, Duration value) {
        Objects.requireNonNull(value, key);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(key + " debe ser mayor que 0: " + value.toSeconds());
        }
    }
}
