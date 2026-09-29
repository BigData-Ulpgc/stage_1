package es.ulpgc.bigdata.control;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ControlFilesTest {

    @TempDir Path tmp;

    private Path controlDir;

    @BeforeEach
    void setUp() {
        controlDir = tmp.resolve("data/control");            // aún no existe
    }

    private ControlFiles open() {
        return new ControlFiles(controlDir);
    }

    private String read(String file) throws IOException {
        return Files.readString(controlDir.resolve(file), StandardCharsets.UTF_8);
    }

    private void write(String file, String content) throws IOException {
        Files.createDirectories(controlDir);
        Files.writeString(controlDir.resolve(file), content, StandardCharsets.UTF_8);
    }

    // --- Criterios del reto --------------------------------------------------

    @Test
    void marcarDosVecesNoRompeReadyToIndexNiDuplicaLaLinea() throws IOException {
        ControlFiles control = open();
        control.markDownloaded(1342);
        control.markDownloaded(1342);

        assertEquals(List.of(1342), control.readyToIndex());
        assertEquals("1342\n", read(ControlFiles.DOWNLOADED_FILE));   // una sola línea
    }

    @Test
    void descargadoPeroNoIndexadoApareceComoPendiente() {
        ControlFiles control = open();
        control.markDownloaded(84);
        control.markDownloaded(1342);
        control.markIndexed(84);

        assertEquals(List.of(1342), control.readyToIndex());
    }

    // --- Conjuntos ----------------------------------------------------------------

    @Test
    void readyToIndexEsLaDiferenciaDeConjuntosOrdenada() {
        ControlFiles control = open();
        for (int id : new int[] {2000, 84, 1342, 11}) {
            control.markDownloaded(id);
        }
        control.markIndexed(1342);
        control.markIndexed(7);                                         // indexado sin descargar: no afecta

        assertEquals(List.of(11, 84, 2000), control.readyToIndex());
    }

    @Test
    void ficherosConDuplicadosSeLeenComoConjunto() throws IOException {
        write(ControlFiles.DOWNLOADED_FILE, "84\n1342\n84\n84\n");
        write(ControlFiles.INDEXED_FILE, "84\n84\n");

        ControlFiles control = open();

        assertEquals(Set.of(84, 1342), control.downloaded());
        assertEquals(List.of(1342), control.readyToIndex());
    }

    @Test
    void sinFicherosTodoEstaVacioYNoSeCreaNada() {
        ControlFiles control = open();

        assertEquals(Set.of(), control.downloaded());
        assertEquals(List.of(), control.readyToIndex());
        assertFalse(Files.exists(controlDir));
    }

    // --- Persistencia ---------------------------------------------------------------

    @Test
    void lasMarcasSobrevivenAReabrir() {
        ControlFiles first = open();
        first.markDownloaded(84);
        first.markDownloaded(1342);
        first.markIndexed(84);

        ControlFiles reopened = open();                                  // "otra ejecución"

        assertTrue(reopened.isDownloaded(1342));
        assertTrue(reopened.isIndexed(84));
        assertFalse(reopened.isIndexed(1342));
        assertEquals(List.of(1342), reopened.readyToIndex());
    }

    @Test
    void anadeAlFinalSinBorrarLoQueHabia() throws IOException {
        write(ControlFiles.DOWNLOADED_FILE, "84\n");

        open().markDownloaded(1342);

        assertEquals("84\n1342\n", read(ControlFiles.DOWNLOADED_FILE));
    }

    // --- Líneas inválidas --------------------------------------------------------------

    @Test
    void ignoraLineasVaciasYQueNoSonIds() throws IOException {
        write(ControlFiles.DOWNLOADED_FILE, "84\n\n   \nabc\n-5\n0084\n99999999999\n1342\n");

        assertEquals(Set.of(84, 1342), open().downloaded());
    }

    @Test
    void aceptaEspaciosYSaltosDeLineaDeWindows() throws IOException {
        write(ControlFiles.DOWNLOADED_FILE, "84\r\n 1342 \n");

        assertEquals(Set.of(84, 1342), open().downloaded());
    }

    @Test
    void unaUltimaLineaCortadaSeIgnora() throws IOException {
        // El programa murió escribiendo "1342\n": sólo llegó "13".
        write(ControlFiles.DOWNLOADED_FILE, "84\n13");

        ControlFiles control = open();

        assertEquals(Set.of(84), control.downloaded());                 // el 13 NO cuenta
    }

    @Test
    void laLineaCortadaNoSePegaALaSiguienteMarca() throws IOException {
        write(ControlFiles.DOWNLOADED_FILE, "84\n13");

        open().markDownloaded(2000);

        // Sin el recorte, el fichero quedaría "84\n132000\n": un id inventado.
        assertEquals("84\n2000\n", read(ControlFiles.DOWNLOADED_FILE));
        assertEquals(Set.of(84, 2000), open().downloaded());
    }

    // --- Validación y encapsulación -------------------------------------------------------

    @Test
    void idNegativoSeRechaza() {
        ControlFiles control = open();
        assertThrows(IllegalArgumentException.class, () -> control.markDownloaded(-1));
        assertFalse(Files.exists(controlDir));
    }

    @Test
    void losConjuntosDevueltosNoSePuedenModificar() {
        ControlFiles control = open();
        control.markDownloaded(84);

        assertThrows(UnsupportedOperationException.class, () -> control.downloaded().add(1));
        assertThrows(UnsupportedOperationException.class, () -> control.readyToIndex().add(1));
    }
}