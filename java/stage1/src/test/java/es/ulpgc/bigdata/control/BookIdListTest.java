package es.ulpgc.bigdata.control;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BookIdListTest {

    @TempDir Path tmp;

    private Path write(String content) throws IOException {
        Path file = tmp.resolve("book_ids.txt");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void leeLosIdsEnOrdenIgnorandoComentariosYLineasVacias() throws IOException {
        Path file = write("# Dataset común\n1342\n\n84\n  11  \n# otro comentario\n2701\n");

        assertEquals(List.of(1342, 84, 11, 2701), BookIdList.load(file));
    }

    @Test
    void quitaRepetidosConservandoLaPrimeraAparicion() throws IOException {
        assertEquals(List.of(84, 11), BookIdList.load(write("84\n11\n84\n")));
    }

    @Test
    void unaLineaQueNoEsUnIdEsUnError() throws IOException {
        Path file = write("1342\n84a\n");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> BookIdList.load(file));
        assertTrue(e.getMessage().contains("línea 2"), e.getMessage());
    }
}