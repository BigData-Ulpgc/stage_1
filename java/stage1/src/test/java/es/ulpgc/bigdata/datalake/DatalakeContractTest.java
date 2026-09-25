package es.ulpgc.bigdata.datalake;

import es.ulpgc.bigdata.model.BookLocation;
import es.ulpgc.bigdata.model.RawBook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DatalakeContractTest {

    @Test
    void bookBasedDatalakeCumpleElContratoDatalake(@TempDir Path tempDir) {
        // Fíjate: tipo declarado como Datalake, no como BookBasedDatalake.
        // El test no sabe (ni le importa) cuál es la implementación concreta.
        Datalake datalake = new BookBasedDatalake(tempDir);

        assertEquals("book", datalake.name());

        BookLocation location = datalake.save(new RawBook(10, "h", "b"));
        assertNotNull(location);

        Optional<BookLocation> found = datalake.locate(10);
        assertTrue(found.isPresent());

        List<Integer> ids = datalake.listBookIds();
        assertEquals(List.of(10), ids);
    }

    @Test
    void listBookIdsIgnoraCarpetasSinLibroValido(@TempDir Path tempDir) throws Exception {
        Datalake datalake = new BookBasedDatalake(tempDir);
        datalake.save(new RawBook(10, "h", "b"));

        // carpeta basura: existe pero no tiene header.txt/body.txt
        java.nio.file.Files.createDirectories(tempDir.resolve("basura"));

        assertEquals(List.of(10), datalake.listBookIds());
    }
}