package es.ulpgc.bigdata.datamart.index;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.WriteModel;
import org.bson.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * Índice invertido en MongoDB (shared/SPEC.md, sección 6):
 *
 *   base de datos:  search_engine
 *   colección:      inverted_index
 *   documentos:     {"term": "boat", "postings": [10, 20]}
 *   índice único sobre "term": un solo documento por término.
 *
 * addDocument acumula en memoria; flush envía TODOS los términos pendientes en una
 * sola operación bulkWrite, con un upsert + $addToSet por término (sin duplicados).
 * Mongo no garantiza el orden del array, así que postings ordena antes de devolver.
 */
public class MongoInvertedIndex implements InvertedIndex {

    public static final String DEFAULT_DATABASE = "search_engine";
    public static final String DEFAULT_COLLECTION = "inverted_index";

    private static final String TERM = "term";
    private static final String POSTINGS = "postings";

    private final MongoClient client;
    private final MongoDatabase database;
    private final String collectionName;
    private final MongoCollection<Document> collection;

    /** Ids añadidos desde el último flush, por término. */
    private final Map<String, SortedSet<Integer>> pending = new HashMap<>();

    /** false tras clear: el índice único se recrea antes de la siguiente escritura. */
    private boolean uniqueIndexReady = false;

    /** Base y colección del SPEC. */
    public MongoInvertedIndex(String connectionString) {
        this(connectionString, DEFAULT_DATABASE, DEFAULT_COLLECTION);
    }

    /** Base y colección configurables (los tests usan una base propia). */
    public MongoInvertedIndex(String connectionString, String databaseName, String collectionName) {
        Objects.requireNonNull(connectionString, "connectionString");
        this.collectionName = Objects.requireNonNull(collectionName, "collectionName");
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(connectionString))
                // Si Mongo no está arrancado, fallar en 3 s en vez de esperar 30 s.
                .applyToClusterSettings(b -> b.serverSelectionTimeout(3, TimeUnit.SECONDS))
                .build();
        this.client = MongoClients.create(settings);
        this.database = client.getDatabase(Objects.requireNonNull(databaseName, "databaseName"));
        this.collection = database.getCollection(collectionName);
        try {
            ensureUniqueIndex();                       // además comprueba que Mongo responde
        } catch (RuntimeException e) {
            client.close();                            // no dejar la conexión abierta si el constructor falla
            throw e;
        }
    }

    @Override
    public String name() {
        return "mongo";
    }

    /** Un documento por término: dos upserts del mismo término nunca crean dos documentos. */
    private void ensureUniqueIndex() {
        collection.createIndex(Indexes.ascending(TERM), new IndexOptions().unique(true));
        uniqueIndexReady = true;
    }

    // ------------------------------------------------------------------
    // addDocument y postings
    // ------------------------------------------------------------------

    /** Sólo en memoria: no habla con Mongo hasta flush. */
    @Override
    public void addDocument(int bookId, Set<String> terms) {
        if (bookId < 0) {
            throw new IllegalArgumentException("book_id negativo: " + bookId);
        }
        Objects.requireNonNull(terms, "terms");
        for (String term : terms) {
            pending.computeIfAbsent(term, t -> new TreeSet<>()).add(bookId);
        }
    }

    /** Un solo documento de Mongo + lo pendiente, ordenado y sin repetir. */
    @Override
    public List<Integer> postings(String term) {
        if (term == null) {
            return List.of();
        }
        SortedSet<Integer> ids = new TreeSet<>();      // Mongo guarda el array en orden de llegada
        Document doc = collection.find(Filters.eq(TERM, term)).first();
        if (doc != null) {
            List<Object> stored = doc.getList(POSTINGS, Object.class);
            if (stored != null) {
                for (Object id : stored) {
                    ids.add(((Number) id).intValue()); // int32 o int64 (Python/C pueden guardar int64)
                }
            }
        }
        SortedSet<Integer> notYetSaved = pending.get(term);
        if (notYetSaved != null) {
            ids.addAll(notYetSaved);
        }
        return List.copyOf(ids);
    }

    // ------------------------------------------------------------------
    // Persistencia
    // ------------------------------------------------------------------

    /**
     * Un upsert por término con $addToSet + $each: si el documento no existe se crea;
     * si existe, sólo se añaden los ids que no estuvieran. Todos van en UN bulkWrite,
     * así que son unas pocas idas y vueltas a Mongo en lugar de una por término.
     */
    @Override
    public void flush() {
        if (pending.isEmpty()) {
            return;
        }
        if (!uniqueIndexReady) {
            ensureUniqueIndex();
        }
        List<WriteModel<Document>> updates = new ArrayList<>(pending.size());
        UpdateOptions upsert = new UpdateOptions().upsert(true);
        for (Map.Entry<String, SortedSet<Integer>> entry : pending.entrySet()) {
            updates.add(new UpdateOneModel<>(
                    Filters.eq(TERM, entry.getKey()),
                    Updates.addEachToSet(POSTINGS, new ArrayList<>(entry.getValue())),
                    upsert));
        }
        collection.bulkWrite(updates, new BulkWriteOptions().ordered(false));
        pending.clear();                                // sólo si Mongo aceptó todo; si falla, se puede reintentar
    }

    /** Borra la colección entera; el índice único se recrea antes de la siguiente escritura. */
    @Override
    public void clear() {
        pending.clear();
        collection.drop();
        uniqueIndexReady = false;
    }

    /**
     * Bytes que Mongo dice ocupar en disco para esta colección: datos (storageSize)
     * más índices (totalIndexSize). 0 si la colección no existe.
     *
     * Antes se pide un fsync: WiredTiger sólo pasa los datos al fichero de la colección
     * en cada checkpoint (unos 60 s); justo después de escribir, storageSize aún sería
     * el de la colección casi vacía.
     */
    @Override
    public long diskUsageBytes() {
        List<String> names = database.listCollectionNames().into(new ArrayList<>());
        if (!names.contains(collectionName)) {
            return 0;
        }
        try {
            client.getDatabase("admin").runCommand(new Document("fsync", 1));
        } catch (RuntimeException e) {
            // Sin permisos para fsync (p. ej. un Mongo gestionado): el número puede ir retrasado.
        }
        Document stats = collection.aggregate(List.of(
                new Document("$collStats", new Document("storageStats", new Document())))).first();
        if (stats == null) {
            return 0;
        }
        Document storage = stats.get("storageStats", Document.class);
        return number(storage, "storageSize") + number(storage, "totalIndexSize");
    }

    private static long number(Document doc, String field) {
        Object value = doc == null ? null : doc.get(field);
        return value instanceof Number n ? n.longValue() : 0;
    }

    /** Cierra la conexión. No hace flush: eso lo decide quien usa el índice. */
    @Override
    public void close() {
        client.close();
    }
}