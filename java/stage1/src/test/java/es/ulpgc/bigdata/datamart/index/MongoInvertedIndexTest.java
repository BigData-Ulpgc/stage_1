package es.ulpgc.bigdata.datamart.index;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Necesita un MongoDB arrancado (docker compose up -d). Si no lo hay, estos tests
 * se SALTAN en vez de fallar, para que "mvn test" funcione sin Docker.
 *
 * Dirección: variable de entorno MONGO_URI, o mongodb://localhost:27017.
 * Usa la base "search_engine_test" y una colección nueva por test: nunca toca el índice real.
 */
class MongoInvertedIndexTest {

    private static final String URI =
            System.getenv().getOrDefault("MONGO_URI", "mongodb://localhost:27017");
    private static final String TEST_DB = "search_engine_test";

    private static Boolean available;

    private String collectionName;
    private MongoInvertedIndex index;
    private MongoClient rawClient;                       // para mirar lo que hay de verdad en Mongo
    private MongoCollection<Document> raw;

    /** ¿Responde Mongo? Se pregunta una vez y se recuerda. */
    private static synchronized boolean mongoAvailable() {
        if (available == null) {
            MongoClientSettings quick = MongoClientSettings.builder()
                    .applyConnectionString(new ConnectionString(URI))
                    .applyToClusterSettings(b -> b.serverSelectionTimeout(1500, TimeUnit.MILLISECONDS))
                    .build();
            try (MongoClient probe = MongoClients.create(quick)) {
                probe.getDatabase("admin").runCommand(new Document("ping", 1));
                available = true;
            } catch (RuntimeException e) {
                available = false;
            }
        }
        return available;
    }

    @BeforeEach
    void setUp() {
        assumeTrue(mongoAvailable(), "MongoDB no está disponible en " + URI + ": test saltado");
        collectionName = "test_" + System.nanoTime();
        index = open();
        rawClient = MongoClients.create(URI);
        raw = rawClient.getDatabase(TEST_DB).getCollection(collectionName);
    }

    @AfterEach
    void tearDown() {
        if (index != null) {
            index.clear();                               // no dejar colecciones de prueba
            index.close();
        }
        if (rawClient != null) {
            rawClient.close();
        }
    }

    private MongoInvertedIndex open() {
        return new MongoInvertedIndex(URI, TEST_DB, collectionName);
    }

    private static void addPaperBooks(InvertedIndex index) {
        index.addDocument(10, Set.of("red", "boat", "sails"));
        index.addDocument(20, Set.of("blue", "boat", "sails", "fast"));
        index.addDocument(30, Set.of("red", "island"));
    }

    private List<Object> storedArray(String term) {
        Document doc = raw.find(new Document("term", term)).first();
        return doc == null ? null : doc.getList("postings", Object.class);
    }

    // --- Criterios del reto --------------------------------------------------

    @Test
    void indexarDosVecesNoDuplicaIds() {
        index.addDocument(10, Set.of("boat"));
        index.addDocument(10, Set.of("boat"));               // dos veces en el mismo lote
        index.flush();
        index.addDocument(10, Set.of("boat"));               // y otra en un lote posterior
        index.flush();

        assertEquals(List.of(10), index.postings("boat"));
        assertEquals(1, storedArray("boat").size());          // también en Mongo, no sólo al leer
        assertEquals(1, raw.countDocuments(new Document("term", "boat")));
    }

    @Test
    void cerrarYVolverAAbrirConservaElIndice() {
        addPaperBooks(index);
        index.flush();
        index.close();

        index = open();                                       // "otra ejecución del programa"

        assertEquals(List.of(10, 20), index.postings("boat"));
        assertEquals(List.of(10, 30), index.postings("red"));
        assertEquals(List.of(30), index.postings("island"));
    }

    @Test
    void clearLimpiaElBackendEntreRepeticiones() {
        addPaperBooks(index);
        index.flush();
        assertTrue(index.diskUsageBytes() > 0);

        index.clear();                                        // "siguiente repetición del benchmark"

        assertEquals(List.of(), index.postings("boat"));
        assertEquals(0, index.diskUsageBytes());
        assertEquals(0, raw.countDocuments());

        addPaperBooks(index);                                 // la segunda repetición funciona igual
        index.flush();
        assertEquals(List.of(10, 20), index.postings("boat"));
        assertTrue(hasUniqueIndexOnTerm(), "clear debe dejar el índice único recreado al volver a escribir");
    }

    // --- Estructura en Mongo --------------------------------------------------------

    private boolean hasUniqueIndexOnTerm() {
        for (Document idx : raw.listIndexes().into(new ArrayList<>())) {
            if (new Document("term", 1).equals(idx.get("key", Document.class))
                    && Boolean.TRUE.equals(idx.getBoolean("unique"))) {
                return true;
            }
        }
        return false;
    }

    @Test
    void existeUnIndiceUnicoSobreTerm() {
        assertTrue(hasUniqueIndexOnTerm());

        raw.insertOne(new Document("term", "boat").append("postings", List.of(1)));
        assertThrows(com.mongodb.MongoWriteException.class,
                () -> raw.insertOne(new Document("term", "boat").append("postings", List.of(2))));
    }

    @Test
    void unDocumentoPorTerminoConElFormatoDelSpec() {
        addPaperBooks(index);
        index.flush();

        assertEquals(6, raw.countDocuments());
        Document boat = raw.find(new Document("term", "boat")).first();
        assertNotNull(boat);
        assertEquals("boat", boat.getString("term"));
        assertEquals(Set.of(10, 20), Set.copyOf(boat.getList("postings", Integer.class)));
    }

    @Test
    void postingsSaleOrdenadaAunqueMongoGuardeEnOrdenDeLlegada() {
        index.addDocument(30, Set.of("red"));
        index.flush();
        index.addDocument(10, Set.of("red"));
        index.flush();

        assertEquals(List.of(30, 10), storedArray("red"));    // así queda en Mongo
        assertEquals(List.of(10, 30), index.postings("red"));  // así lo devuelve el índice
    }

    // --- addDocument acumula hasta flush -----------------------------------------

    @Test
    void loAnadidoSeVeAntesDelFlushPeroNoLlegaAMongoSinEl() {
        addPaperBooks(index);

        assertEquals(List.of(10, 20), index.postings("boat"));
        assertEquals(0, raw.countDocuments());
    }

    @Test
    void flushSinCambiosNoEscribeNada() {
        index.flush();
        assertEquals(0, raw.countDocuments());
    }

    @Test
    void terminoInexistenteDaListaVacia() {
        addPaperBooks(index);
        index.flush();
        assertEquals(List.of(), index.postings("whale"));
        assertEquals(List.of(), index.postings("Boat"));      // Mongo distingue mayúsculas
    }

    @Test
    void leeIdsGuardadosComoInt64PorOtroPrograma() {
        raw.insertOne(new Document("term", "boat").append("postings", List.of(20L, 10L, 20L)));

        assertEquals(List.of(10, 20), index.postings("boat"));
    }
}