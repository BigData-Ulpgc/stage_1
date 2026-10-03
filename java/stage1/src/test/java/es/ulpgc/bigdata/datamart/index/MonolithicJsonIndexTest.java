package es.ulpgc.bigdata.datamart.index;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MonolithicJsonIndexTest {

    @TempDir Path tmp;

    private Path jsonFile;

    @BeforeEach
    void setUp() {
        jsonFile = tmp.resolve("datamarts/inverted_index.json");   // the folder does not exist yet
    }

    private MonolithicJsonIndex open() {
        return new MonolithicJsonIndex(jsonFile);
    }

    private static void addPaperBooks(InvertedIndex index) {
        index.addDocument(10, Set.of("red", "boat", "sails"));
        index.addDocument(20, Set.of("blue", "boat", "sails", "fast"));
        index.addDocument(30, Set.of("red", "island"));
    }

    private String json() throws IOException {
        return Files.readString(jsonFile, StandardCharsets.UTF_8);
    }

    // --- Challenge criteria --------------------------------------------------

    @Test
    void cerrarYReabrirConservaLosPostings() {
        try (MonolithicJsonIndex index = open()) {
            addPaperBooks(index);
            index.flush();
        }

        try (MonolithicJsonIndex reopened = open()) {
            assertEquals(List.of(10, 20), reopened.postings("boat"));
            assertEquals(List.of(10, 30), reopened.postings("red"));
            assertEquals(List.of(30), reopened.postings("island"));
            assertEquals(6, reopened.termCount());
        }
    }

    @Test
    void elJsonTieneUnaClavePorTerminoYListasOrdenadas() throws IOException {
        try (MonolithicJsonIndex index = open()) {
            index.addDocument(30, Set.of("red", "island"));   // unsorted on purpose
            index.addDocument(20, Set.of("blue", "boat", "sails", "fast"));
            index.addDocument(10, Set.of("red", "boat", "sails"));
            index.flush();
        }

        // Exactly Appendix A: terms in alphabetical order, ascending ids.
        assertEquals("{\"blue\":[20],\"boat\":[10,20],\"fast\":[20],"
                + "\"island\":[30],\"red\":[10,30],\"sails\":[10,20]}", json());
    }

    @Test
    void anadirYHacerFlushNoPierdeTerminosAnteriores() {
        try (MonolithicJsonIndex index = open()) {
            index.addDocument(10, Set.of("red", "boat"));
            index.flush();
        }
        try (MonolithicJsonIndex index = open()) {          // another run of the program
            index.addDocument(20, Set.of("boat", "blue"));
            index.flush();
        }

        try (MonolithicJsonIndex reopened = open()) {
            assertEquals(List.of(10), reopened.postings("red"));       // from the first flush
            assertEquals(List.of(10, 20), reopened.postings("boat"));  // mix of both
            assertEquals(List.of(20), reopened.postings("blue"));      // from the second
        }
    }

    // --- flush ----------------------------------------------------------------

    @Test
    void sinFlushNadaLlegaAlDisco() {
        try (MonolithicJsonIndex index = open()) {
            addPaperBooks(index);
            assertEquals(List.of(10, 20), index.postings("boat"));      // in memory, yes
        }                                                               // close does NOT flush

        assertFalse(Files.exists(jsonFile));
        try (MonolithicJsonIndex reopened = open()) {
            assertEquals(List.of(), reopened.postings("boat"));
        }
    }

    @Test
    void flushNoDejaElTemporal() throws IOException {
        try (MonolithicJsonIndex index = open()) {
            addPaperBooks(index);
            index.flush();
        }
        try (Stream<Path> files = Files.list(jsonFile.getParent())) {
            assertEquals(List.of("inverted_index.json"),
                    files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void flushSinCambiosNoReescribeElFichero() throws IOException {
        try (MonolithicJsonIndex index = open()) {
            addPaperBooks(index);
            index.flush();
            Files.writeString(jsonFile, "{\"marca\":[1]}");            // if it is rewritten, it disappears

            index.addDocument(10, Set.of("red"));                      // 10 was already in red
            index.flush();

            assertEquals("{\"marca\":[1]}", json());
        }
    }

    // --- Loading --------------------------------------------------------------

    @Test
    void cargaUnJsonDesordenadoConEspaciosYDuplicados() throws IOException {
        // This is how another program could write it (for example, Python's json.dump with indent).
        Files.createDirectories(jsonFile.getParent());
        Files.writeString(jsonFile, """
                {
                  "sails": [20, 10, 20],
                  "boat":  [20, 10]
                }
                """);

        try (MonolithicJsonIndex index = open()) {
            assertEquals(List.of(10, 20), index.postings("sails"));
            assertEquals(List.of(10, 20), index.postings("boat"));
        }
    }

    @Test
    void unJsonCorruptoNoSeSustituyePorUnIndiceVacio() throws IOException {
        Files.createDirectories(jsonFile.getParent());
        Files.writeString(jsonFile, "{\"boat\":[10,20");               // cut halfway

        assertThrows(UncheckedIOException.class, this::open);
        assertEquals("{\"boat\":[10,20", json());                      // the file is still intact
    }

    // --- clear and size ---------------------------------------------------------

    @Test
    void clearBorraMemoriaYFichero() {
        try (MonolithicJsonIndex index = open()) {
            addPaperBooks(index);
            index.flush();

            index.clear();

            assertFalse(Files.exists(jsonFile));
            assertEquals(List.of(), index.postings("boat"));
            assertEquals(0, index.diskUsageBytes());
        }
        try (MonolithicJsonIndex reopened = open()) {
            assertEquals(List.of(), reopened.postings("boat"));
        }
    }

    @Test
    void diskUsageEsElTamanoDelJson() throws IOException {
        try (MonolithicJsonIndex index = open()) {
            assertEquals(0, index.diskUsageBytes());
            addPaperBooks(index);
            index.flush();
            assertEquals(Files.size(jsonFile), index.diskUsageBytes());
            assertEquals(json().getBytes(StandardCharsets.UTF_8).length, index.diskUsageBytes());
        }
    }
}