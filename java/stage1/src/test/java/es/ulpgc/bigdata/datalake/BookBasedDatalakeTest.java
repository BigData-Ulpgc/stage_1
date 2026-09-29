package es.ulpgc.bigdata.datalake;

import es.ulpgc.bigdata.model.BookLocation;
import es.ulpgc.bigdata.model.RawBook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BookBasedDatalakeTest {

    @Test
    void guardaUnLibroYCreaAmbosFicheros(@TempDir Path tempDir) throws IOException {
        BookBasedDatalake datalake = new BookBasedDatalake(tempDir);
        RawBook book = new RawBook(1342, "Title: Pride and Prejudice", "It is a truth...");

        BookLocation location = datalake.save(book);

        assertTrue(Files.exists(location.headerPath()));
        assertTrue(Files.exists(location.bodyPath()));
        assertEquals("Title: Pride and Prejudice",
                Files.readString(location.headerPath()));
        assertEquals("It is a truth...",
                Files.readString(location.bodyPath()));
    }

    @Test
    void localizaUnLibroGuardado(@TempDir Path tempDir) {
        BookBasedDatalake datalake = new BookBasedDatalake(tempDir);
        datalake.save(new RawBook(1342, "header", "body"));

        Optional<BookLocation> found = datalake.locate(1342);

        assertTrue(found.isPresent());
        assertEquals(1342, found.get().id());
    }

    @Test
    void noLocalizaUnLibroInexistente(@TempDir Path tempDir) {
        BookBasedDatalake datalake = new BookBasedDatalake(tempDir);

        Optional<BookLocation> found = datalake.locate(999);

        assertTrue(found.isEmpty());
    }

    @Test
    void guardarDosVecesElMismoLibroNoRompeNada(@TempDir Path tempDir) {
        BookBasedDatalake datalake = new BookBasedDatalake(tempDir);
        RawBook book = new RawBook(1342, "header v1", "body v1");

        datalake.save(book);
        BookLocation second = datalake.save(book);

        assertTrue(datalake.locate(1342).isPresent());
        assertEquals(1342, second.id());
    }

    @TempDir Path tmp;
    @Test
    void carpetaConCerosALaIzquierdaNoDuplicaElId() throws IOException {
        Path root = tmp.resolve("book");
        Datalake datalake = new BookBasedDatalake(root);
        datalake.save(new RawBook(84, "H", "B"));
        crearLibroFalso(root.resolve("0084"));

        assertEquals(List.of(84), datalake.listBookIds());
    }

    @Test
    void idDemasiadoGrandeNoRompeElListado() throws IOException {
        Path root = tmp.resolve("book");
        Datalake datalake = new BookBasedDatalake(root);
        datalake.save(new RawBook(84, "H", "B"));
        crearLibroFalso(root.resolve("99999999999"));

        assertEquals(List.of(84), datalake.listBookIds());
    }

    /** Carpeta con header y body, como si fuera un libro, pero creada a mano. */
    private static void crearLibroFalso(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("header.txt"), "x");
        Files.writeString(dir.resolve("body.txt"), "x");
    }
}