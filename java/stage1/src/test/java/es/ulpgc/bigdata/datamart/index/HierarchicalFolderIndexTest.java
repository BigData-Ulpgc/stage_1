package es.ulpgc.bigdata.datamart.index;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HierarchicalFolderIndexTest {

    @TempDir Path tmp;

    private Path root;

    @BeforeEach
    void setUp() {
        root = tmp.resolve("datamarts/inverted_index");       // aún no existe
    }

    private HierarchicalFolderIndex open() {
        return new HierarchicalFolderIndex(root);
    }

    private static void addPaperBooks(InvertedIndex index) {
        index.addDocument(10, Set.of("red", "boat", "sails"));
        index.addDocument(20, Set.of("blue", "boat", "sails", "fast"));
        index.addDocument(30, Set.of("red", "island"));
    }

    private String read(String relative) throws IOException {
        return Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
    }

    // --- Criterios del reto --------------------------------------------------

    @Test
    void adventureSeGuardaEnAAdventureTxt() throws IOException {
        try (HierarchicalFolderIndex index = open()) {
            assertEquals(root.resolve("A").resolve("adventure.txt"), index.termFile("adventure"));

            index.addDocument(7, Set.of("adventure"));
            index.flush();
        }
        assertEquals("7\n", read("A/adventure.txt"));
    }

    @Test
    void reindexarElMismoLibroNoGeneraDuplicados() throws IOException {
        try (HierarchicalFolderIndex index = open()) {
            index.addDocument(10, Set.of("boat"));
            index.flush();
            index.addDocument(10, Set.of("boat"));              // otra vez, ya guardado
            index.addDocument(10, Set.of("boat"));              // y otra, en el mismo lote
            index.flush();
        }
        try (HierarchicalFolderIndex index = open()) {        // y en otra ejecución
            index.addDocument(10, Set.of("boat"));
            index.flush();
            assertEquals(List.of(10), index.postings("boat"));
        }
        assertEquals("10\n", read("B/boat.txt"));
    }

    @Test
    void terminoInexistenteDaListaVacia() {
        try (HierarchicalFolderIndex index = open()) {
            assertEquals(List.of(), index.postings("whale"));   // ni la carpeta existe
            addPaperBooks(index);
            index.flush();
            assertEquals(List.of(), index.postings("whale"));
            assertEquals(List.of(), index.postings("bottle"));  // existe B/, pero no bottle.txt
        }
    }

    @Test
    void clearDejaElDirectorioSinIndice() {
        try (HierarchicalFolderIndex index = open()) {
            addPaperBooks(index);
            index.flush();
            assertTrue(Files.exists(root.resolve("B/boat.txt")));

            index.clear();

            assertFalse(Files.exists(root));
            assertEquals(0, index.diskUsageBytes());
            assertEquals(List.of(), index.postings("boat"));
        }
        try (HierarchicalFolderIndex reopened = open()) {
            assertEquals(List.of(), reopened.postings("boat"));
        }
    }

    // --- Formato en disco -------------------------------------------------------

    @Test
    void unIdPorLineaOrdenadosAunqueLleguenDesordenados() throws IOException {
        try (HierarchicalFolderIndex index = open()) {
            index.addDocument(30, Set.of("red"));
            index.flush();
            index.addDocument(10, Set.of("red"));
            index.addDocument(20, Set.of("red"));
            index.flush();
        }
        assertEquals("10\n20\n30\n", read("R/red.txt"));
    }

    @Test
    void elIndiceDelApendiceAEnDisco() throws IOException {
        try (HierarchicalFolderIndex index = open()) {
            addPaperBooks(index);
            index.flush();
        }
        assertEquals("20\n", read("B/blue.txt"));
        assertEquals("10\n20\n", read("B/boat.txt"));
        assertEquals("20\n", read("F/fast.txt"));
        assertEquals("30\n", read("I/island.txt"));
        assertEquals("10\n30\n", read("R/red.txt"));
        assertEquals("10\n20\n", read("S/sails.txt"));
        try (HierarchicalFolderIndex index = open()) {
            assertEquals(6, index.termFiles().size());
        }
    }

    @Test
    void terminoQueEmpiezaPorDigitoVaEnLaCarpetaDelDigito() throws IOException {
        try (HierarchicalFolderIndex index = open()) {
            assertEquals(root.resolve("1").resolve("1813.txt"), index.termFile("1813"));
            index.addDocument(1342, Set.of("1813", "3rd"));
            index.flush();
        }
        assertEquals("1342\n", read("1/1813.txt"));
        assertEquals("1342\n", read("3/3rd.txt"));
    }

    // --- addDocument acumula hasta flush ---------------------------------------

    @Test
    void loAnadidoSeVeAntesDelFlushPeroNoLlegaAlDiscoSinEl() {
        try (HierarchicalFolderIndex index = open()) {
            addPaperBooks(index);
            assertEquals(List.of(10, 20), index.postings("boat"));   // en memoria sí
            assertFalse(Files.exists(root));                          // en disco nada
        }                                                             // close NO hace flush
        try (HierarchicalFolderIndex reopened = open()) {
            assertEquals(List.of(), reopened.postings("boat"));
        }
    }

    @Test
    void postingsUneLoGuardadoConLoPendiente() {
        try (HierarchicalFolderIndex index = open()) {
            index.addDocument(10, Set.of("boat"));
            index.flush();
            index.addDocument(20, Set.of("boat"));                    // pendiente

            assertEquals(List.of(10, 20), index.postings("boat"));
        }
    }

    @Test
    void anadirEnOtraEjecucionMezclaConLoQueYaHabia() throws IOException {
        try (HierarchicalFolderIndex index = open()) {
            index.addDocument(10, Set.of("red", "boat"));
            index.flush();
        }
        try (HierarchicalFolderIndex index = open()) {
            index.addDocument(20, Set.of("boat", "blue"));
            index.flush();
        }
        assertEquals("10\n", read("R/red.txt"));
        assertEquals("10\n20\n", read("B/boat.txt"));
        assertEquals("20\n", read("B/blue.txt"));
    }

    // --- Sólo se toca lo necesario ----------------------------------------------

    @Test
    void flushSoloReescribeLosTerminosQueCambian() throws IOException {
        try (HierarchicalFolderIndex index = open()) {
            addPaperBooks(index);
            index.flush();
            FileTime old = FileTime.fromMillis(0);
            Files.setLastModifiedTime(root.resolve("R/red.txt"), old);
            Files.setLastModifiedTime(root.resolve("B/boat.txt"), old);

            index.addDocument(40, Set.of("boat"));                    // sólo cambia boat
            index.flush();

            assertEquals(old, Files.getLastModifiedTime(root.resolve("R/red.txt")));
            assertNotEquals(old, Files.getLastModifiedTime(root.resolve("B/boat.txt")));
        }
    }

    @Test
    void postingsSoloLeeElFicheroDelTermino() throws IOException {
        try (HierarchicalFolderIndex index = open()) {
            addPaperBooks(index);
            index.flush();
        }
        Files.writeString(root.resolve("R/red.txt"), "esto no es un id\n");   // otro término, corrupto

        try (HierarchicalFolderIndex index = open()) {
            assertEquals(List.of(10, 20), index.postings("boat"));            // no le afecta
            assertThrows(java.io.UncheckedIOException.class, () -> index.postings("red"));
        }
    }

    @Test
    void flushNoDejaTemporales() {
        try (HierarchicalFolderIndex index = open()) {
            addPaperBooks(index);
            index.flush();
            assertTrue(index.termFiles().stream().noneMatch(p -> p.toString().endsWith(".tmp")));
            assertEquals(6, index.termFiles().size());
        }
    }

    // --- Entradas raras ---------------------------------------------------------

    @Test
    void leeFicherosEscritosPorOtroProgramaDesordenadosYConDuplicados() throws IOException {
        Files.createDirectories(root.resolve("B"));
        Files.writeString(root.resolve("B/boat.txt"), "20\n10\n20\n\n");

        try (HierarchicalFolderIndex index = open()) {
            assertEquals(List.of(10, 20), index.postings("boat"));
        }
    }

    @Test
    void terminosQueNoSalenDelTokenizerSeRechazan() {
        try (HierarchicalFolderIndex index = open()) {
            index.addDocument(10, Set.of("boat"));
            index.flush();

            assertEquals(List.of(), index.postings("Boat"));                  // no es un término
            assertEquals(List.of(), index.postings("../B/boat"));
            assertThrows(IllegalArgumentException.class, () -> index.addDocument(20, Set.of("../evil")));
            assertEquals(List.of(10), index.postings("boat"));               // nada a medias
        }
    }

    @Test
    void diskUsageEsLaSumaDeLosFicheros() throws IOException {
        try (HierarchicalFolderIndex index = open()) {
            assertEquals(0, index.diskUsageBytes());
            addPaperBooks(index);
            index.flush();

            long sum = 0;
            for (Path p : index.termFiles()) {
                sum += Files.size(p);
            }
            assertEquals(sum, index.diskUsageBytes());
            assertEquals(27, index.diskUsageBytes());      // 3+6+3+3+6+6 bytes: "20\n", "10\n20\n"...
        }
    }
}