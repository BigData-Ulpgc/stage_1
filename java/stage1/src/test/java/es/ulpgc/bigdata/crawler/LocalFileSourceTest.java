package es.ulpgc.bigdata.crawler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LocalFileSourceTest {

    @TempDir Path tmp;

    @Test
    void devuelveElFicheroPgIdTalCual() throws IOException {
        String text = "Title: Ñandú\r\n*** START OF X ***\r\ncuerpo\r\n*** END OF X ***\r\n";   // \r\n and UTF-8 untouched
        Files.writeString(tmp.resolve("pg42.txt"), text, StandardCharsets.UTF_8);

        assertEquals(Optional.of(text), new LocalFileSource(tmp).fetch(42));
    }

    @Test
    void unFicheroQueNoExisteEsUnLibroNoDisponible() {
        assertEquals(Optional.empty(), new LocalFileSource(tmp).fetch(7));
    }

    @Test
    void otroErrorDeLecturaEsUnaExcepcion() throws IOException {
        Files.createDirectory(tmp.resolve("pg9.txt"));                       // exists, but cannot be read as a file

        assertThrows(UncheckedIOException.class, () -> new LocalFileSource(tmp).fetch(9));
    }
}
