package es.ulpgc.bigdata.crawler;

import es.ulpgc.bigdata.datalake.BookBasedDatalake;
import es.ulpgc.bigdata.datalake.Datalake;
import es.ulpgc.bigdata.datalake.RangeBasedDatalake;
import es.ulpgc.bigdata.datalake.TimeBasedDatalake;
import es.ulpgc.bigdata.model.BookLocation;
import es.ulpgc.bigdata.model.RawBook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BookDownloader sin red: la fuente es una lambda. El splitter y el datalake
 * son los reales, porque no tocan la red y son deterministas.
 */
class BookDownloaderTest {

    private static final String VALID_TEXT = String.join("\n",
            "Title: Pride and Prejudice",
            "Author: Jane Austen",
            "Language: English",
            "",
            "*** START OF THE PROJECT GUTENBERG EBOOK PRIDE AND PREJUDICE ***",
            "It is a truth universally acknowledged, that a single man…",
            "*** END OF THE PROJECT GUTENBERG EBOOK PRIDE AND PREJUDICE ***",
            "Licencia y pie de página que deben descartarse.");

    private static final String TEXT_WITHOUT_MARKERS =
            "Title: Algo\nAuthor: Alguien\n\nTexto sin ningún marcador de Gutenberg.";

    @TempDir Path tmp;

    private BookSplitter splitter;
    private Datalake datalake;

    @BeforeEach
    void setUp() {
        splitter = new BookSplitter();
        datalake = new BookBasedDatalake(tmp.resolve("datalake"));
    }

    /** Cuenta ficheros de cualquier tipo, incluidos .tmp: "no crea archivos" es literal. */
    private long filesUnder(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (Stream<Path> all = Files.walk(dir)) {
            return all.filter(Files::isRegularFile).count();
        }
    }

    // --- Criterios del reto ------------------------------------------------

    @Test
    void libroValidoDevuelveSuLocationConLoQueProduceElSplitter() throws IOException {
        BookDownloader downloader =
                new BookDownloader(id -> Optional.of(VALID_TEXT), splitter, datalake);

        BookLocation loc = downloader.download(1342).orElseThrow();

        RawBook expected = splitter.split(1342, VALID_TEXT).orElseThrow();
        assertEquals(1342, loc.id());
        assertEquals(expected.header(), Files.readString(loc.headerPath(), StandardCharsets.UTF_8));
        assertEquals(expected.body(), Files.readString(loc.bodyPath(), StandardCharsets.UTF_8));
        assertTrue(expected.body().contains("It is a truth universally acknowledged"));
        assertEquals(List.of(1342), datalake.listBookIds());
    }

    @Test
    void errorHttpNoCreaArchivos() throws IOException {
        // Así se ve un 404 desde fuera del cliente: Optional vacío.
        BookDownloader downloader =
                new BookDownloader(id -> Optional.empty(), splitter, datalake);

        assertTrue(downloader.download(999999).isEmpty());
        assertEquals(0L, filesUnder(tmp));
    }

    @Test
    void textoSinMarcadoresNoCreaArchivos() throws IOException {
        BookDownloader downloader =
                new BookDownloader(id -> Optional.of(TEXT_WITHOUT_MARKERS), splitter, datalake);

        assertTrue(downloader.download(1342).isEmpty());
        assertEquals(0L, filesUnder(tmp));
    }

    // --- Comportamiento adicional -----------------------------------------

    @Test
    void pideALaFuenteElIdCorrectoUnaSolaVez() {
        List<Integer> requested = new ArrayList<>();
        BookSource spy = id -> {
            requested.add(id);
            return Optional.of(VALID_TEXT);
        };

        new BookDownloader(spy, splitter, datalake).download(1342);

        assertEquals(List.of(1342), requested);
    }

    @Test
    void falloDeRedSePropagaYNoCreaArchivos() throws IOException {
        BookSource networkDown = id -> {
            throw new UncheckedIOException(new ConnectException("sin conexión"));
        };
        BookDownloader downloader = new BookDownloader(networkDown, splitter, datalake);

        assertThrows(UncheckedIOException.class, () -> downloader.download(1342));
        assertEquals(0L, filesUnder(tmp));
    }

    @Test
    void idNegativoNoLlegaALaRed() {
        List<Integer> requested = new ArrayList<>();
        BookSource spy = id -> {
            requested.add(id);
            return Optional.of(VALID_TEXT);
        };
        BookDownloader downloader = new BookDownloader(spy, splitter, datalake);

        assertThrows(IllegalArgumentException.class, () -> downloader.download(-5));
        assertEquals(List.of(), requested);
    }

    @Test
    void funcionaIgualConLasTresEstructuras() {
        List<Datalake> datalakes = List.of(
                new BookBasedDatalake(tmp.resolve("book")),
                new RangeBasedDatalake(tmp.resolve("range")),
                new TimeBasedDatalake(tmp.resolve("time")));

        for (Datalake d : datalakes) {
            BookDownloader downloader = new BookDownloader(id -> Optional.of(VALID_TEXT), splitter, d);

            BookLocation loc = downloader.download(1342).orElseThrow();

            assertEquals(Optional.of(loc), d.locate(1342), d.name());
        }
    }
}