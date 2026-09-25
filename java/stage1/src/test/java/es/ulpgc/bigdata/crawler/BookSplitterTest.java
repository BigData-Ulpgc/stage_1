package es.ulpgc.bigdata.crawler;

import es.ulpgc.bigdata.model.RawBook;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class BookSplitterTest {

    private final BookSplitter splitter = new BookSplitter();

    @Test
    void separaHeaderYBodyCorrectamenteEnUnTextoValido() {
        String raw = """
                Title: Red Boat
                Author: Jane Doe

                *** START OF THE PROJECT GUTENBERG EBOOK RED BOAT ***

                red boat sails

                *** END OF THE PROJECT GUTENBERG EBOOK RED BOAT ***

                This is the footer, licensing info, etc.
                """;

        Optional<RawBook> result = splitter.split(10, raw);

        assertTrue(result.isPresent());
        RawBook book = result.get();
        assertEquals(10, book.id());
        assertEquals("Title: Red Boat\nAuthor: Jane Doe", book.header());
        assertEquals("red boat sails", book.body());
        assertFalse(book.body().contains("footer"));
        assertFalse(book.body().contains("START OF"));
    }

    @Test
    void aceptaLaVarianteThisAdemasDeThe() {
        String raw = """
                Header info

                *** START OF THIS PROJECT GUTENBERG EBOOK RED BOAT ***

                red boat sails

                *** END OF THIS PROJECT GUTENBERG EBOOK RED BOAT ***

                footer
                """;

        assertTrue(splitter.split(10, raw).isPresent());
    }

    @Test
    void seDescartaSiFaltaElMarcadorDeInicio() {
        String raw = """
                Header info

                red boat sails

                *** END OF THE PROJECT GUTENBERG EBOOK RED BOAT ***

                footer
                """;

        assertTrue(splitter.split(10, raw).isEmpty());
    }

    @Test
    void seDescartaSiFaltaElMarcadorDeFin() {
        String raw = """
                Header info

                *** START OF THE PROJECT GUTENBERG EBOOK RED BOAT ***

                red boat sails
                """;

        assertTrue(splitter.split(10, raw).isEmpty());
    }

    @Test
    void normalizaCrlfANs() {
        String raw = "Header\r\n\r\n*** START OF THE PROJECT GUTENBERG EBOOK X ***\r\n\r\n"
                + "red boat\r\n\r\n*** END OF THE PROJECT GUTENBERG EBOOK X ***\r\n";

        Optional<RawBook> result = splitter.split(10, raw);

        assertTrue(result.isPresent());
        assertFalse(result.get().body().contains("\r"));
        assertEquals("red boat", result.get().body());
    }
}