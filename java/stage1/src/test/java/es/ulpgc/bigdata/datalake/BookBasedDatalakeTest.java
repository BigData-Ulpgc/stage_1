package es.ulpgc.bigdata.datalake;

import es.ulpgc.bigdata.model.BookLocation;
import es.ulpgc.bigdata.model.RawBook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}